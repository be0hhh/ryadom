package ru.ryadom.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.time.Clock

class ServerConfig(
    val installKey: String,
    val liveKitUrl: String,
    val liveKitApiKey: String,
    val liveKitApiSecret: String,
) {
    init {
        require(installKey.isNotBlank() && installKey.length <= 512) { "Некорректный RYADOM_INSTALL_KEY" }
        require(liveKitApiKey.isNotBlank() && liveKitApiKey.length <= 256) { "Некорректный LIVEKIT_API_KEY" }
        require(liveKitApiSecret.isNotBlank() && liveKitApiSecret.length <= 1024) { "Некорректный LIVEKIT_API_SECRET" }
        val uri = URI(liveKitUrl)
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "LIVEKIT_URL должен быть защищённым адресом wss://"
        }
    }

    companion object {
        fun fromEnvironment(): ServerConfig {
            fun required(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("Не задана переменная $name")
            return ServerConfig(required("RYADOM_INSTALL_KEY"), required("LIVEKIT_URL"), required("LIVEKIT_API_KEY"), required("LIVEKIT_API_SECRET"))
        }
    }
}

fun Application.ryadomModule(config: ServerConfig, clock: Clock = Clock.systemUTC()) {
    val rooms = Rooms(config, clock)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ApiError> { call, cause -> call.respond(cause.status, ErrorResponse(cause.message)) }
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            // Do not include request bodies, invite keys, tokens or backend secrets in logs/errors.
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Ошибка сервера. Попробуйте ещё раз."))
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorResponse("Адрес не найден."))
        }
    }
    routing {
        post("/rooms") { call.respond(HttpStatusCode.Created, rooms.create(call.bearer())) }
        post("/rooms/{id}/join") { call.respond(rooms.join(call.roomId(), call.boundedJson<JoinRequest>())) }
        get("/rooms/{id}/state") { call.respond(rooms.state(call.roomId(), call.bearer())) }
        post("/rooms/{id}/presenter") {
            val key = call.bearer()
            call.respond(rooms.presenter(call.roomId(), key, call.boundedJson<PresenterRequest>()))
        }
    }
}

private fun ApplicationCall.roomId(): String = parameters["id"] ?: throw ApiError(HttpStatusCode.NotFound, "Комната не найдена.")

private fun ApplicationCall.bearer(): String {
    val header = request.headers[HttpHeaders.Authorization]
        ?: throw ApiError(HttpStatusCode.Unauthorized, "Нужен ключ доступа.")
    if (header.length > 520 || !header.startsWith("Bearer ", ignoreCase = true)) {
        throw ApiError(HttpStatusCode.Unauthorized, "Неверный ключ доступа.")
    }
    return header.substring(7).takeIf { it.isNotBlank() && it.length <= 512 }
        ?: throw ApiError(HttpStatusCode.Unauthorized, "Неверный ключ доступа.")
}

private suspend inline fun <reified T> ApplicationCall.boundedJson(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw ApiError(HttpStatusCode.UnsupportedMediaType, "Нужен запрос в формате JSON.")
    }
    val bytes = receiveChannel().readRemaining(4097).readByteArray()
    if (bytes.size > 4096) throw ApiError(HttpStatusCode.PayloadTooLarge, "Запрос слишком большой.")
    return try {
        Json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: SerializationException) {
        throw ApiError(HttpStatusCode.BadRequest, "Некорректный запрос JSON.")
    } catch (_: CharacterCodingException) {
        throw ApiError(HttpStatusCode.BadRequest, "Нужен текст UTF-8.")
    }
}

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, host = "127.0.0.1", port = 8080) { ryadomModule(config) }.start(wait = true)
}
