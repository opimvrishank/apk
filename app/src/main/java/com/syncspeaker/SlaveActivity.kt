package com.syncspeaker

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.syncspeaker.databinding.ActivitySlaveBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SlaveActivity : AppCompatActivity() {
    private lateinit var b: ActivitySlaveBinding

    private val sync = SyncEngine()
    private val player = SyncedPlayer()
    private val files = ConcurrentHashMap<String, MediaFile>() // id -> with localPath when downloaded

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var loopJob: Job? = null
    private var syncJob: Job? = null

    private val myId = UUID.randomUUID().toString().take(6)
    private val myName = "${Build.MODEL ?: "phone"}-$myId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySlaveBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnConnect.setOnClickListener {
            val ip = b.etManualIp.text?.toString()?.trim().orEmpty()
            if (ip.isEmpty()) {
                log("Enter master IP first")
                return@setOnClickListener
            }
            connect(ip)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        player.stop()
    }

    // ---------- Connection / control loop ----------

    private fun connect(ip: String) {
        disconnect()
        b.tvStatus.text = "Connecting to $ip..."
        loopJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, Protocol.CONTROL_PORT), 5000)
                }
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                runOnUiThread {
                    b.tvStatus.text = "Connected to $ip"
                    log("Connected")
                }
                send(Protocol.hello(myId, myName))
                startSyncLoop()

                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (isActive) {
                    val line = r.readLine() ?: break
                    handle(line, ip)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect", e)
                runOnUiThread {
                    b.tvStatus.text = "Disconnected"
                    log("Connection error: ${e.message}")
                }
            } finally {
                disconnect()
            }
        }
    }

    private fun disconnect() {
        syncJob?.cancel(); syncJob = null
        loopJob?.cancel(); loopJob = null
        writer?.runCatching { close() }; writer = null
        socket?.runCatching { close() }; socket = null
    }

    private val writeLock = Any()
    private fun send(msg: String) {
        synchronized(writeLock) {
            try { writer?.println(msg) } catch (_: Exception) {}
        }
    }

    private fun handle(line: String, masterIp: String) {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (o.optString("type")) {
            Protocol.PONG -> {
                val t1 = o.optLong("t1")
                val t2 = o.optLong("t2")
                val t3 = o.optLong("t3")
                val t4 = System.nanoTime()
                sync.addSample(t1, t2, t3, t4)
                runOnUiThread {
                    val ms = sync.bestRttNanos / 1_000_000.0
                    b.tvOffset.text = "Synced: rtt=${"%.1f".format(ms)}ms, samples=${sync.sampleCount}"
                }
            }
            Protocol.LIBRARY -> {
                val arr = o.optJSONArray("files") ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val id = f.optString("id")
                        val name = f.optString("name")
                        val size = f.optLong("size")
                        if (!files.containsKey(id)) {
                            files[id] = MediaFile(id, name, size, "")
                            ensureDownloaded(id, name, masterIp)
                        }
                    }
                }
            }
            Protocol.PLAY -> {
                val id = o.optString("fileId")
                val atMasterNanos = o.optLong("atNanos")
                val mf = files[id]
                if (mf == null || mf.localPath.isEmpty()) {
                    runOnUiThread { log("PLAY received but file $id not yet downloaded") }
                    return
                }
                val localTarget = sync.localNanosForMasterNanos(atMasterNanos)
                player.playAt(File(mf.localPath), localTarget) {
                    runOnUiThread { log("Play err: ${it.message}") }
                }
                runOnUiThread { log("Playing ${mf.name}") }
            }
            Protocol.STOP -> {
                player.stop()
                runOnUiThread { log("Stopped") }
            }
        }
    }

    // ---------- Periodic clock sync ----------

    private fun startSyncLoop() {
        syncJob = lifecycleScope.launch(Dispatchers.IO) {
            // Burst of 12 fast pings, then 1 ping every 5s to track drift
            repeat(12) {
                send(Protocol.ping(System.nanoTime()))
                delay(80)
            }
            while (isActive) {
                send(Protocol.ping(System.nanoTime()))
                delay(5_000)
            }
        }
    }

    // ---------- File download ----------

    private suspend fun ensureDownloaded(id: String, name: String, masterIp: String) {
        try {
            val cacheDir = File(filesDir, "media").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(cacheDir, "${id}_$safe")
            if (!out.exists() || out.length() == 0L) {
                runOnUiThread { log("Downloading $name...") }
                val url = URL("http://$masterIp:${Protocol.MEDIA_PORT}/file/$id")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 5000
                con.readTimeout = 30000
                con.connect()
                if (con.responseCode != 200) error("HTTP ${con.responseCode}")
                con.inputStream.use { input ->
                    out.outputStream().use { o -> input.copyTo(o) }
                }
                con.disconnect()
            }
            files[id] = MediaFile(id, name, out.length(), out.absolutePath)
            runOnUiThread { log("Ready: $name") }
        } catch (e: Exception) {
            runOnUiThread { log("Download failed for $name: ${e.message}") }
        }
    }

    // ---------- UI ----------

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(msg: String) {
        val t = timeFmt.format(Date())
        b.tvLog.append("[$t] $msg\n")
    }

    companion object { private const val TAG = "Slave" }
}
