package ru.ryadom.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable internal data class ErrorResponse(val error: String)
@Serializable internal data class CreatedRoom(val roomId: String, val key: String)
@Serializable internal data class JoinRequest(val key: String, val identity: String, val name: String)
@Serializable internal data class JoinedRoom(val serverUrl: String, val token: String, val roomId: String)
@Serializable internal data class PresenterRequest(val identity: String, val action: String)
@Serializable internal data class PresenterState(val presenterId: String?)

internal class ApiError(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

/** Process-local admission registry. Restarting the server invalidates invitations. */
internal class Rooms(private val config: ServerConfig, private val clock: Clock) {
    private data class Room(
        val keyHash: ByteArray,
        val expiresAt: Instant,
        val identities: MutableSet<String> = mutableSetOf(),
        var presenterId: String? = null,
        var leaseUntil: Instant? = null,
    )

    private val random = SecureRandom()
    private val installKeyHash = hash(config.installKey)
    private val rooms = mutableMapOf<String, Room>()
    private val tokens = LiveKitTokens(config, clock)

    @Synchronized fun create(installKey: String): CreatedRoom {
        if (!matches(installKeyHash, installKey)) throw ApiError(HttpStatusCode.Unauthorized, "Неверный ключ установки.")
        val now = clock.instant()
        rooms.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        if (rooms.size >= 128) throw ApiError(HttpStatusCode.TooManyRequests, "Слишком много комнат. Попробуйте позже.")
        var id: String
        do { id = randomString(18) } while (rooms.containsKey(id))
        val key = randomString(32)
        rooms[id] = Room(hash(key), now.plusSeconds(7200))
        return CreatedRoom(id, key)
    }

    @Synchronized fun join(roomId: String, request: JoinRequest): JoinedRoom {
        val room = authorizedRoom(roomId, request.key)
        val identity = validIdentity(request.identity)
        val name = request.name.trim()
        if (name.isBlank() || name.length > 40 || name.any { it.isISOControl() }) {
            throw ApiError(HttpStatusCode.BadRequest, "Имя должно содержать от 1 до 40 символов без переноса строк.")
        }
        if (identity !in room.identities && room.identities.size >= 2) {
            throw ApiError(HttpStatusCode.Conflict, "В комнате уже двое участников.")
        }
        // Mint before reserving the seat, so a token failure cannot consume one.
        val token = tokens.issue(roomId, identity, name, room.expiresAt)
        room.identities += identity
        return JoinedRoom(config.liveKitUrl, token, roomId)
    }

    @Synchronized fun state(roomId: String, key: String): PresenterState {
        val room = authorizedRoom(roomId, key)
        expireLease(room)
        return PresenterState(room.presenterId)
    }

    @Synchronized fun presenter(roomId: String, key: String, request: PresenterRequest): PresenterState {
        val room = authorizedRoom(roomId, key)
        val identity = validIdentity(request.identity)
        if (identity !in room.identities) throw ApiError(HttpStatusCode.Forbidden, "Сначала войдите в комнату.")
        expireLease(room)
        when (request.action) {
            "claim" -> {
                if (room.presenterId != null && room.presenterId != identity) occupied()
                room.presenterId = identity
                room.leaseUntil = clock.instant().plusSeconds(20)
            }
            "renew" -> {
                if (room.presenterId != identity) throw ApiError(HttpStatusCode.Conflict, "Право показа уже истекло или принадлежит другому участнику.")
                room.leaseUntil = clock.instant().plusSeconds(20)
            }
            "release" -> {
                if (room.presenterId != null && room.presenterId != identity) occupied()
                room.presenterId = null
                room.leaseUntil = null
            }
            else -> throw ApiError(HttpStatusCode.BadRequest, "Неизвестное действие с показом экрана.")
        }
        return PresenterState(room.presenterId)
    }

    private fun authorizedRoom(roomId: String, key: String): Room {
        if (!roomId.matches(Regex("[A-Za-z0-9_-]{24}"))) throw ApiError(HttpStatusCode.NotFound, "Комната не найдена.")
        val room = rooms[roomId] ?: throw ApiError(HttpStatusCode.NotFound, "Комната не найдена или уже закрыта.")
        if (key.length > 512 || !matches(room.keyHash, key)) throw ApiError(HttpStatusCode.Unauthorized, "Неверный ключ комнаты.")
        if (!clock.instant().isBefore(room.expiresAt)) throw ApiError(HttpStatusCode.Gone, "Срок комнаты истёк. Создайте новую.")
        return room
    }

    private fun expireLease(room: Room) {
        if (room.leaseUntil?.let { !clock.instant().isBefore(it) } == true) {
            room.presenterId = null
            room.leaseUntil = null
        }
    }

    private fun validIdentity(identity: String): String {
        val parsed = if (identity.length == 36) runCatching { UUID.fromString(identity) }.getOrNull() else null
        if (parsed == null || parsed.toString() != identity) throw ApiError(HttpStatusCode.BadRequest, "Неверный идентификатор устройства.")
        return identity
    }

    private fun occupied(): Nothing = throw ApiError(HttpStatusCode.Conflict, "Другой участник уже показывает экран.")
    private fun randomString(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
    private fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun matches(expectedHash: ByteArray, supplied: String): Boolean = MessageDigest.isEqual(expectedHash, hash(supplied))
}
