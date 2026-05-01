package com.syncspeaker

import org.json.JSONArray
import org.json.JSONObject

/**
 * Newline-delimited JSON protocol over TCP.
 * Master listens on CONTROL_PORT for commands, MEDIA_PORT for HTTP file downloads.
 */
object Protocol {
    const val CONTROL_PORT = 7531
    const val MEDIA_PORT = 7532

    // Type tags
    const val PING = "ping"      // slave -> master: { t1 }
    const val PONG = "pong"      // master -> slave: { t1, t2, t3 }
    const val HELLO = "hello"    // slave -> master: { id, name }
    const val LIBRARY = "lib"    // master -> slave: { files: [...] }
    const val PLAY = "play"      // master -> slave: { fileId, atNanos } (master nanoTime)
    const val STOP = "stop"

    fun ping(t1: Long) = JSONObject().apply {
        put("type", PING); put("t1", t1)
    }.toString()

    fun pong(t1: Long, t2: Long, t3: Long) = JSONObject().apply {
        put("type", PONG); put("t1", t1); put("t2", t2); put("t3", t3)
    }.toString()

    fun hello(id: String, name: String) = JSONObject().apply {
        put("type", HELLO); put("id", id); put("name", name)
    }.toString()

    fun library(files: List<MediaFile>): String {
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("id", f.id); put("name", f.name); put("size", f.size)
            })
        }
        return JSONObject().apply { put("type", LIBRARY); put("files", arr) }.toString()
    }

    fun play(fileId: String, atNanos: Long) = JSONObject().apply {
        put("type", PLAY); put("fileId", fileId); put("atNanos", atNanos)
    }.toString()

    fun stop() = JSONObject().apply { put("type", STOP) }.toString()
}

data class MediaFile(
    val id: String,
    val name: String,
    val size: Long,
    val localPath: String   // empty on slave until downloaded
)
