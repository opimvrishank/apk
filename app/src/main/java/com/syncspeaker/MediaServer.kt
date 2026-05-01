package com.syncspeaker

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal HTTP/1.1 GET server.
 * Routes:
 *   GET /file/<id>  -> bytes of registered file
 *   GET /list       -> JSON listing (debug)
 */
class MediaServer(private val files: ConcurrentHashMap<String, MediaFile>) {

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            try {
                val ss = ServerSocket(Protocol.MEDIA_PORT)
                serverSocket = ss
                Log.i(TAG, "MediaServer listening on ${Protocol.MEDIA_PORT}")
                while (isActive) {
                    val client = try { ss.accept() } catch (_: Exception) { break }
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaServer error", e)
            }
        }
    }

    fun stop() {
        scope.cancel()
        serverSocket?.runCatching { close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val inR = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = inR.readLine() ?: return
                // Drain headers
                while (true) {
                    val line = inR.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    write(s.getOutputStream(), 400, "Bad Request")
                    return
                }
                val path = URLDecoder.decode(parts[1], "UTF-8")
                when {
                    path.startsWith("/file/") -> {
                        val id = path.removePrefix("/file/")
                        val mf = files[id]
                        val f = mf?.localPath?.let { File(it) }
                        if (mf == null || f == null || !f.exists()) {
                            write(s.getOutputStream(), 404, "Not Found")
                        } else {
                            writeFile(s.getOutputStream(), f)
                        }
                    }
                    else -> write(s.getOutputStream(), 404, "Not Found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "client handler", e)
            }
        }
    }

    private fun write(os: OutputStream, code: Int, reason: String) {
        val pw = PrintWriter(os)
        pw.print("HTTP/1.1 $code $reason\r\n")
        pw.print("Content-Length: 0\r\n")
        pw.print("Connection: close\r\n\r\n")
        pw.flush()
    }

    private fun writeFile(os: OutputStream, f: File) {
        val pw = PrintWriter(os)
        pw.print("HTTP/1.1 200 OK\r\n")
        pw.print("Content-Type: application/octet-stream\r\n")
        pw.print("Content-Length: ${f.length()}\r\n")
        pw.print("Connection: close\r\n\r\n")
        pw.flush()
        f.inputStream().use { it.copyTo(os) }
        os.flush()
    }

    companion object { private const val TAG = "MediaServer" }
}
