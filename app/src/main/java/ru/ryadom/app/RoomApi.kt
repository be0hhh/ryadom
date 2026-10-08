package ru.ryadom.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable data class CreatedRoom(val roomId: String, val key: String)
@Serializable data class JoinRequest(val key: String, val identity: String, val name: String)
@Serializable data class JoinedRoom(val serverUrl: String, val token: String, val roomId: String)
@Serializable data class PresenterRequest(val identity: String, val action: String)
@Serializable data class PresenterState(val presenterId: String? = null)
@Serializable private data class ApiError(val error: String)

class RoomApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun create(setup: Invitation): Invitation {
        val result = json.decodeFromString<CreatedRoom>(request(setup, "/rooms", "{}"))
        return Invitation(setup.server, result.key, result.roomId)
    }
    suspend fun join(invite: Invitation, identity: String, name: String): JoinedRoom =
        json.decodeFromString(request(invite, "/rooms/${invite.roomId}/join", json.encodeToString(JoinRequest(invite.key, identity, name))))
    suspend fun state(invite: Invitation): PresenterState =
        json.decodeFromString(request(invite, "/rooms/${invite.roomId}/state", null))
    suspend fun presenter(invite: Invitation, identity: String, action: String): PresenterState =
        json.decodeFromString(request(invite, "/rooms/${invite.roomId}/presenter", json.encodeToString(PresenterRequest(identity, action))))

    private suspend fun request(invite: Invitation, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(invite.server + path)
            .header("Authorization", "Bearer ${invite.key}")
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        client.newCall(request).execute().use { response ->
            val source = response.body?.source()
            require(source == null || !source.request(32769)) { "Слишком большой ответ сервера" }
            val raw = source?.readUtf8().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { json.decodeFromString<ApiError>(raw).error }.getOrNull()
                throw IllegalStateException(error ?: "Сервер недоступен (${response.code})")
            }
            raw
        }
    }
}
