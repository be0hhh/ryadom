package ru.ryadom.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

private const val FIRST = "cfe98369-0066-40fa-ae2f-319e46a1df2c"
private const val SECOND = "2d6fb412-f926-4c02-8bad-c91d077a4cd6"
private const val THIRD = "529bf51e-e1a6-40e6-9dad-65fed1484d76"

private val config = ServerConfig("install-secret-test-only", "wss://home.example.ts.net", "api-test", "api-secret-test-only")

private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

private data class Invite(val roomId: String, val key: String)
private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
private suspend fun HttpClient.create(): Invite {
    val response = post("/rooms") { bearerAuth(config.installKey) }
    assertEquals(HttpStatusCode.Created, response.status)
    val json = response.json()
    return Invite(json.getValue("roomId").jsonPrimitive.content, json.getValue("key").jsonPrimitive.content)
}
private suspend fun HttpClient.join(invite: Invite, id: String = FIRST, name: String = "Друг", key: String = invite.key): HttpResponse =
    post("/rooms/${invite.roomId}/join") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("key", key); put("identity", id); put("name", name) }.toString())
    }
private suspend fun HttpClient.state(invite: Invite, key: String = invite.key): HttpResponse =
    get("/rooms/${invite.roomId}/state") { bearerAuth(key) }
private suspend fun HttpClient.presenter(invite: Invite, id: String, action: String): HttpResponse =
    post("/rooms/${invite.roomId}/presenter") {
        bearerAuth(invite.key)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("identity", id); put("action", action) }.toString())
    }
private suspend fun HttpResponse.assertError(status: HttpStatusCode) {
    assertEquals(status, this.status)
    assertEquals(ContentType.Application.Json, contentType()?.withoutParameters())
    val body = json()
    assertEquals(setOf("error"), body.keys)
    assertTrue(body.getValue("error").jsonPrimitive.content.any { it in 'А'..'я' })
}
private suspend fun HttpResponse.presenterId(): String? = json().getValue("presenterId").jsonPrimitive.contentOrNull

class RoomApiTest {
    @Test fun `creation requires installation key and produces independent opaque invites`() = testApplication {
        application { ryadomModule(config) }
        client.post("/rooms").assertError(HttpStatusCode.Unauthorized)
        client.post("/rooms") { bearerAuth("incorrect") }.assertError(HttpStatusCode.Unauthorized)
        val one = client.create()
        val two = client.create()
        assertNotEquals(one.roomId, two.roomId)
        assertNotEquals(one.key, two.key)
        assertTrue(one.key.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(one.roomId.matches(Regex("[A-Za-z0-9_-]{24}")))
        client.join(one, key = two.key).assertError(HttpStatusCode.Unauthorized)
    }

    @Test fun `room admits two identities keeps both seats and permits reconnect`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        assertEquals(HttpStatusCode.OK, client.join(invite).status)
        assertEquals(HttpStatusCode.OK, client.join(invite, SECOND).status)
        client.join(invite, THIRD).assertError(HttpStatusCode.Conflict)
        assertEquals(HttpStatusCode.OK, client.join(invite, FIRST).status)
        assertEquals(HttpStatusCode.OK, client.join(invite, SECOND).status)
    }

    @Test fun `simultaneous joins cannot reserve a third seat`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        val statuses = coroutineScope {
            listOf(FIRST, SECOND, THIRD).map { id -> async { client.join(invite, id).status } }.awaitAll()
        }
        assertEquals(2, statuses.count { it == HttpStatusCode.OK })
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict })
    }

    @Test fun `malformed identities and blank or oversized names do not consume seats`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        for (id in listOf("", "someone", "1-1-1-1-1")) client.join(invite, id).assertError(HttpStatusCode.BadRequest)
        for (name in listOf("", "   ", "я".repeat(41), "x\nname")) client.join(invite, name = name).assertError(HttpStatusCode.BadRequest)
        assertEquals(HttpStatusCode.OK, client.join(invite, name = "я".repeat(40)).status)
        assertEquals(HttpStatusCode.OK, client.join(invite, SECOND).status)
    }

    @Test fun `malformed and oversized json receive bounded human errors`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        client.post("/rooms/${invite.roomId}/join") {
            contentType(ContentType.Application.Json); setBody("{")
        }.assertError(HttpStatusCode.BadRequest)
        client.post("/rooms/${invite.roomId}/join") {
            contentType(ContentType.Application.Json); setBody("x".repeat(4097))
        }.assertError(HttpStatusCode.PayloadTooLarge)
        client.get("/missing").assertError(HttpStatusCode.NotFound)
    }

    @Test fun `room expires at two hours for join state and presenter`() = testApplication {
        val clock = MutableClock()
        application { ryadomModule(config, clock) }
        val invite = client.create()
        client.join(invite)
        clock.advance(7199)
        assertEquals(HttpStatusCode.OK, client.state(invite).status)
        clock.advance(1)
        client.join(invite).assertError(HttpStatusCode.Gone)
        client.state(invite).assertError(HttpStatusCode.Gone)
        client.presenter(invite, FIRST, "claim").assertError(HttpStatusCode.Gone)
    }

    @Test fun `presenter requires invite and a joined identity`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        client.state(invite, "bad").assertError(HttpStatusCode.Unauthorized)
        client.presenter(invite, FIRST, "claim").assertError(HttpStatusCode.Forbidden)
        client.post("/rooms/${invite.roomId}/presenter") {
            contentType(ContentType.Application.Json)
            setBody("""{"identity":"$FIRST","action":"claim"}""")
        }.assertError(HttpStatusCode.Unauthorized)
        client.join(invite)
        client.presenter(invite, FIRST, "unknown").assertError(HttpStatusCode.BadRequest)
        assertNull(client.state(invite).presenterId())
    }

    @Test fun `claim cannot steal presenter and release cannot clear other lease`() = testApplication {
        application { ryadomModule(config) }
        val invite = client.create()
        client.join(invite); client.join(invite, SECOND)
        assertEquals(FIRST, client.presenter(invite, FIRST, "claim").presenterId())
        client.presenter(invite, SECOND, "claim").assertError(HttpStatusCode.Conflict)
        client.presenter(invite, SECOND, "renew").assertError(HttpStatusCode.Conflict)
        client.presenter(invite, SECOND, "release").assertError(HttpStatusCode.Conflict)
        assertEquals(FIRST, client.state(invite).presenterId())
        assertNull(client.presenter(invite, FIRST, "release").presenterId())
        assertEquals(SECOND, client.presenter(invite, SECOND, "claim").presenterId())
    }

    @Test fun `renew extends twenty second lease and expiry permits another presenter`() = testApplication {
        val clock = MutableClock()
        application { ryadomModule(config, clock) }
        val invite = client.create()
        client.join(invite); client.join(invite, SECOND)
        client.presenter(invite, FIRST, "claim")
        clock.advance(19)
        assertEquals(FIRST, client.presenter(invite, FIRST, "renew").presenterId())
        clock.advance(19)
        assertEquals(FIRST, client.state(invite).presenterId())
        clock.advance(1)
        assertNull(client.state(invite).presenterId())
        client.presenter(invite, FIRST, "renew").assertError(HttpStatusCode.Conflict)
        assertEquals(SECOND, client.presenter(invite, SECOND, "claim").presenterId())
    }

    @Test fun `issued token has valid backend signature and least privilege room grants`() = testApplication {
        val clock = MutableClock()
        application { ryadomModule(config, clock) }
        val invite = client.create()
        val joined = client.join(invite).json()
        assertEquals(config.liveKitUrl, joined.getValue("serverUrl").jsonPrimitive.content)
        assertEquals(invite.roomId, joined.getValue("roomId").jsonPrimitive.content)
        val parts = joined.getValue("token").jsonPrimitive.content.split('.')
        assertEquals(3, parts.size)
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]))).jsonObject
        assertEquals("HS256", header.getValue("alg").jsonPrimitive.content)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.liveKitApiSecret.toByteArray(), "HmacSHA256"))
        assertContentEquals(mac.doFinal("${parts[0]}.${parts[1]}".toByteArray()), decoder.decode(parts[2]))
        val claims = Json.parseToJsonElement(String(decoder.decode(parts[1]))).jsonObject
        assertEquals(config.liveKitApiKey, claims.getValue("iss").jsonPrimitive.content)
        assertEquals(FIRST, claims.getValue("sub").jsonPrimitive.content)
        assertEquals("Друг", claims.getValue("name").jsonPrimitive.content)
        assertEquals(clock.now.epochSecond, claims.getValue("nbf").jsonPrimitive.long)
        assertEquals(clock.now.epochSecond + 600, claims.getValue("exp").jsonPrimitive.long)
        val video = claims.getValue("video").jsonObject
        assertEquals(invite.roomId, video.getValue("room").jsonPrimitive.content)
        assertTrue(video.getValue("roomJoin").jsonPrimitive.boolean)
        assertTrue(video.getValue("canPublish").jsonPrimitive.boolean)
        assertTrue(video.getValue("canSubscribe").jsonPrimitive.boolean)
        assertTrue(video.getValue("canPublishData").jsonPrimitive.boolean)
        assertEquals(setOf("microphone", "screen_share", "screen_share_audio"), video.getValue("canPublishSources").jsonArray.map { it.jsonPrimitive.content }.toSet())
        for (grant in listOf("roomAdmin", "roomCreate", "roomList", "roomRecord", "ingressAdmin")) {
            assertFalse(video[grant]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        assertEquals(2, claims.getValue("roomConfig").jsonObject.getValue("maxParticipants").jsonPrimitive.int)
        assertFalse(joined.toString().contains(config.liveKitApiSecret))
    }
}
