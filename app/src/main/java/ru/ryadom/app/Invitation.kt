package ru.ryadom.app

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class Invitation(val server: String, val key: String, val roomId: String? = null) {
    val isSetup: Boolean get() = roomId == null
    fun toLink(): String = "ryadom://${if (isSetup) "setup" else "join"}?server=${encode(server)}&key=${encode(key)}" +
        (roomId?.let { "&room=${encode(it)}" } ?: "")
    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun parse(raw: String): Invitation? = runCatching {
            require(raw.length <= 8192)
            val uri = URI(raw.trim())
            require(uri.scheme == "ryadom" && uri.host in setOf("setup", "join"))
            require(uri.rawFragment == null && uri.rawUserInfo == null && uri.port == -1 && uri.path.isNullOrEmpty())
            val fields = uri.rawQuery.split('&').map {
                val pair = it.split('=', limit = 2)
                require(pair.size == 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            require(fields.map { it.first }.distinct().size == fields.size)
            val params = fields.toMap()
            require(params.keys == if (uri.host == "setup") setOf("server", "key") else setOf("server", "key", "room"))
            val server = URI(params.getValue("server"))
            require(server.scheme == "https" && server.host?.endsWith(".ts.net") == true)
            require(server.rawUserInfo == null && server.rawQuery == null && server.rawFragment == null)
            require(server.path.isNullOrEmpty() && (server.port == -1 || server.port in 1..65535))
            val key = params.getValue("key")
            require(key.length in 24..256 && key.all { it.isLetterOrDigit() || it in "-_" })
            val room = params["room"]
            require(room == null || (room.length in 1..128 && room.all { it.isLetterOrDigit() || it in "-_" }))
            Invitation(server.toASCIIString(), key, room)
        }.getOrNull()
    }
}
