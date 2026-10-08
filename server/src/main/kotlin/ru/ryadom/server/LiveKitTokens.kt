package ru.ryadom.server

import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HS256 JWT as specified by LiveKit's access-token protocol; secrets remain on this server. */
internal class LiveKitTokens(private val config: ServerConfig, private val clock: Clock) {
    fun issue(room: String, identity: String, name: String, roomExpiresAt: Instant): String {
        val now = clock.instant().epochSecond
        val header = buildJsonObject { put("alg", "HS256"); put("typ", "JWT") }
        val payload = buildJsonObject {
            put("iss", config.liveKitApiKey)
            put("sub", identity)
            put("name", name)
            put("iat", now)
            put("nbf", now)
            put("exp", minOf(now + 600, roomExpiresAt.epochSecond))
            putJsonObject("video") {
                put("room", room)
                put("roomJoin", true)
                put("canPublish", true)
                put("canSubscribe", true)
                put("canPublishData", true)
                putJsonArray("canPublishSources") {
                    add("microphone"); add("screen_share"); add("screen_share_audio")
                }
            }
            // LiveKit applies this configuration when the random room is first auto-created.
            putJsonObject("roomConfig") { put("maxParticipants", 2) }
        }
        val body = "${encode(header.toString().toByteArray(Charsets.UTF_8))}.${encode(payload.toString().toByteArray(Charsets.UTF_8))}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.liveKitApiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "$body.${encode(mac.doFinal(body.toByteArray(Charsets.US_ASCII)))}"
    }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
