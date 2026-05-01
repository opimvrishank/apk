package com.syncspeaker

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.syncspeaker.databinding.ActivityMasterBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MasterActivity : AppCompatActivity() {
    private lateinit var b: ActivityMasterBinding

    private val files = ConcurrentHashMap<String, MediaFile>()
    private val slaves = ConcurrentHashMap<String, SlaveConnection>()
    private lateinit var mediaServer: MediaServer
    private val player = SyncedPlayer()
    private var controlSocket: ServerSocket? = null

    private var currentFileId: String? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMasterBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvIp.text = getLocalIp() ?: "No WiFi IP"
        b.btnPick.setOnClickListener { pickAudio.launch("audio/*") }
        b.btnPlay.setOnClickListener { onPlay() }
        b.btnStop.setOnClickListener { stopPlayback() }

        mediaServer = MediaServer(files)
        mediaServer.start()
        startControlServer()
        log("Master ready. Slaves connect to ${getLocalIp()}:${Protocol.CONTROL_PORT}")
    }

    override fun onDestroy() {
        super.onDestroy()
        controlSocket?.runCatching { close() }
        mediaServer.stop()
        player.stop()
        slaves.values.forEach { it.close() }
    }

    // ---------- File picking ----------

    private fun onFilePicked(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(filesDir, "media").apply { mkdirs() }
                val displayName = queryDisplayName(uri) ?: "song.mp3"
                val id = UUID.randomUUID().toString().take(8)
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outFile = File(cacheDir, "${id}_$safeName")
                contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { out -> input.copyTo(out) }
                } ?: error("can't open uri")

                val mf = MediaFile(id, displayName, outFile.length(), outFile.absolutePath)
                files[id] = mf
                currentFileId = id

                withContext(Dispatchers.Main) {
                    b.tvFile.text = "${mf.name}  (${Formatter.formatShortFileSize(this@MasterActivity, mf.size)})"
                    b.btnPlay.isEnabled = true
                    log("Loaded: ${mf.name}")
                }
                broadcastLibrary()
            } catch (e: Exception) {
                Log.e(TAG, "pick failed", e)
                runOnUiThread { log("Failed: ${e.message}") }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---------- Playback ----------

    private fun onPlay() {
        val id = currentFileId ?: return
        val mf = files[id] ?: return
        // Schedule 1.5s in the future on master clock — gives slaves time to receive,
        // schedule, and pre-start their MediaPlayer prepare() if needed.
        val target = System.nanoTime() + 1_500_000_000L
        val msg = Protocol.play(id, target)
        slaves.values.forEach { it.send(msg) }

        player.playAt(File(mf.localPath), target) { e -> log("local play err: ${e.message}") }
        log("PLAY ${mf.name} at +1.5s on ${slaves.size} slaves")
        b.btnStop.isEnabled = true
    }

    private fun stopPlayback() {
        slaves.values.forEach { it.send(Protocol.stop()) }
        player.stop()
        log("STOP")
        b.btnStop.isEnabled = false
    }

    private fun broadcastLibrary() {
        val msg = Protocol.library(files.values.toList())
        slaves.values.forEach { it.send(msg) }
    }

    // ---------- Control server ----------

    private fun startControlServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(Protocol.CONTROL_PORT)
                controlSocket = ss
                while (isActive) {
                    val socket = try { ss.accept() } catch (_: Exception) { break }
                    val conn = SlaveConnection(socket)
                    conn.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "control server error", e)
            }
        }
    }

    inner class SlaveConnection(private val socket: Socket) {
        private val id = UUID.randomUUID().toString().take(6)
        private var name: String = "slave-$id"
        private var writer: PrintWriter? = null
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun start() {
            slaves[id] = this
            scope.launch { loop() }
            runOnUiThread { updateSlaveCount() }
        }

        private fun loop() {
            try {
                socket.tcpNoDelay = true
                val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer = PrintWriter(socket.getOutputStream(), true)
                // Send current library on connect
                send(Protocol.library(files.values.toList()))
                while (true) {
                    val line = r.readLine() ?: break
                    handle(line)
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        private fun handle(line: String) {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return
            when (o.optString("type")) {
                Protocol.PING -> {
                    val t1 = o.optLong("t1")
                    val t2 = System.nanoTime()
                    // Send PONG synchronously so the t3 timestamp we report
                    // matches the actual write moment closely.
                    synchronized(writeLock) {
                        val t3 = System.nanoTime()
                        try { writer?.println(Protocol.pong(t1, t2, t3)) } catch (_: Exception) {}
                    }
                }
                Protocol.HELLO -> {
                    name = o.optString("name", name)
                    runOnUiThread { log("Slave joined: $name") }
                }
            }
        }

        private val writeLock = Any()
        fun send(msg: String) {
            scope.launch {
                synchronized(writeLock) {
                    try { writer?.println(msg) } catch (_: Exception) {}
                }
            }
        }

        fun close() {
            scope.cancel()
            socket.runCatching { close() }
            slaves.remove(id)
            runOnUiThread {
                updateSlaveCount()
                log("Slave left: $name")
            }
        }
    }

    private fun updateSlaveCount() {
        val n = slaves.size
        b.tvSlaveCount.text = "$n slave${if (n == 1) "" else "s"} connected"
    }

    // ---------- Utilities ----------

    private fun getLocalIp(): String? {
        // Prefer Wi-Fi IP if available
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = wifi?.connectionInfo?.ipAddress ?: 0
        if (raw != 0) {
            return Formatter.formatIpAddress(raw)
        }
        // Fallback: enumerate non-loopback IPv4 addresses
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(msg: String) {
        val t = timeFmt.format(Date())
        b.tvLog.append("[$t] $msg\n")
    }

    companion object { private const val TAG = "Master" }
}
