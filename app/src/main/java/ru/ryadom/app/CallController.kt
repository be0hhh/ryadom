package ru.ryadom.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Process
import android.os.SystemClock
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.org.webrtc.ScreenCapturerAndroid
import java.util.UUID

class RyadomApplication : Application() {
    val controller by lazy { CallController(this) }
}

data class ChatMessage(val id: String, val name: String, val text: String, val mine: Boolean)
@Serializable private data class ChatWire(val id: String, val text: String)
data class CallUiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val sharing: Boolean = false,
    val busySharing: Boolean = false,
    val presenterId: String? = null,
    val microphone: Boolean = true,
    val playback: Boolean = true,
    val playbackSignal: Boolean = false,
    val partner: String? = null,
    val videoTrack: VideoTrack? = null,
    val invite: Invitation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)

class CallController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CallUiState())
    val state = mutable.asStateFlow()
    private val prefs = app.getSharedPreferences("ryadom", 0)
    val identity: String = prefs.getString("identity", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("identity", it).apply()
    }
    val savedName: String get() = prefs.getString("name", "").orEmpty()
    val savedSetup: String get() = prefs.getString("setup", "").orEmpty()
    private val api = RoomApi()
    private val json = Json { ignoreUnknownKeys = true }
    private val audio = AudioPolicy()
    private val mixer = PlaybackMixer(audio)
    private val capture = CaptureState()
    private val captureLock = Mutex()
    var room: Room? = null; private set
    private var connectionJob: Job? = null
    private var pollJob: Job? = null
    private var eventsJob: Job? = null
    private var captureJob: Job? = null
    private var watchdogJob: Job? = null
    private var generation = 0
    private var activeCaptureId: Long? = null
    private var permissionSession = 0
    private var presenterLease: PresenterLease? = null

    fun error(message: String?) { mutable.update { it.copy(error = message) } }

    @SuppressLint("MissingPermission") // Explicit RECORD_AUDIO gate before any publication.
    fun connect(raw: String, name: String, create: Boolean) {
        if (state.value.connected || state.value.connecting) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Разреши микрофон, чтобы начать сеанс"); return
        }
        val input = Invitation.parse(raw)
        if (input == null || (create && !input.isSetup) || (!create && input.isSetup)) {
            error(if (create) "Вставь приватную ссылку настройки сервера" else "Вставь приглашение в комнату"); return
        }
        val displayName = name.trim()
        if (displayName.isEmpty() || displayName.length > 40 || displayName.any { it.isISOControl() }) {
            error("Укажи имя от 1 до 40 символов"); return
        }
        prefs.edit().putString("name", displayName).apply()
        if (create) prefs.edit().putString("setup", input.toLink()).apply()
        val attempt = ++generation
        mutable.value = CallUiState(connecting = true)
        connectionJob = scope.launch {
            try {
                val invite = if (create) api.create(input) else input
                val joined = api.join(invite, identity, displayName)
                if (generation != attempt) return@launch
                val rtc = LiveKit.create(app, options = RoomOptions(adaptiveStream = true, dynacast = true))
                room = rtc
                audio.microphoneEnabled = true; audio.playbackEnabled = true
                rtc.screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
                    // LiveKit screencast expects longest side first, regardless of orientation.
                    isScreencast = true, captureParams = VideoCaptureParameter(1280, 720, 30, false))
                rtc.screenShareTrackPublishDefaults = VideoTrackPublishDefaults(videoEncoding = VideoEncoding(2_000_000, 30))
                eventsJob = scope.launch { rtc.events.collect { event -> if (generation == attempt) handleEvent(event) } }
                rtc.connect(joined.serverUrl, joined.token)
                check(rtc.localParticipant.setMicrophoneEnabled(true)) { "Не удалось включить микрофон" }
                (rtc.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)
                    ?.setAudioBufferCallback(mixer)
                if (generation != attempt) return@launch
                mutable.update { it.copy(connecting = false, connected = true, invite = invite) }
                refreshRemote()
                pollJob = scope.launch { pollPresenter(invite, attempt) }
                watchdogJob = scope.launch {
                    while (generation == attempt && state.value.connected) {
                        val lease = presenterLease
                        if (lease?.hasGrant == true && !lease.isValid(SystemClock.elapsedRealtime())) {
                            captureJob?.cancel()
                            stopSharing()
                            error("Показ остановлен: нет подтверждения сервера. Разреши показ заново.")
                        }
                        delay(1000)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == attempt) {
                    disconnect()
                    error("Не удалось подключиться. Проверь Tailscale, сервер и приглашение.")
                    app.stopService(Intent(app, CallService::class.java))
                }
            }
        }
    }

    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Disconnected -> {
                disconnect(); error("Связь прервалась. Войди по приглашению ещё раз.")
                app.stopService(Intent(app, CallService::class.java))
            }
            is RoomEvent.Reconnecting -> { mutable.update { it.copy(reconnecting = true) }; captureJob?.cancel(); stopSharing() }
            is RoomEvent.Reconnected -> mutable.update { it.copy(reconnecting = false) }
            is RoomEvent.DataReceived -> if (event.topic == "ryadom.chat" && event.data.size <= 8192 && event.participant != null) {
                runCatching { json.decodeFromString<ChatWire>(event.data.toString(Charsets.UTF_8)) }.getOrNull()?.let {
                    if (it.text.isNotBlank() && it.text.length <= 2000 && it.id.length <= 64) {
                        addMessage(ChatMessage(it.id, event.participant?.name ?: "Партнёр", it.text, false))
                    }
                }
            }
            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected,
            is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted -> refreshRemote()
            else -> Unit
        }
    }

    private fun refreshRemote() {
        val partner = room?.remoteParticipants?.values?.firstOrNull()
        val track = partner?.trackPublications?.values?.firstOrNull {
            it.source == Track.Source.SCREEN_SHARE && !it.muted
        }?.track as? VideoTrack
        mutable.update { it.copy(partner = partner?.name, videoTrack = track) }
    }

    private suspend fun pollPresenter(invite: Invitation, attempt: Int) {
        while (scope.isActive && generation == attempt && state.value.connected) {
            val lease = presenterLease
            val captureId = activeCaptureId
            try {
                val requestStarted = SystemClock.elapsedRealtime()
                val presenter = if (lease?.hasGrant == true) api.presenter(invite, identity, "renew") else api.state(invite)
                if (generation != attempt) return
                if (lease === presenterLease && captureId == activeCaptureId) {
                    if (lease?.hasGrant == true) {
                        if (presenter.presenterId != identity || !lease.granted(requestStarted, SystemClock.elapsedRealtime())) {
                            captureJob?.cancel(); stopSharing()
                        }
                    }
                    mutable.update { it.copy(presenterId = presenter.presenterId,
                        playbackSignal = capture.sharing && mixer.lastSignalAt.get() > SystemClock.elapsedRealtime() - 5000) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == attempt && lease === presenterLease && captureId == activeCaptureId && lease?.hasGrant == true) {
                    captureJob?.cancel(); stopSharing()
                    error("Показ остановлен: нет связи с сервером. Разреши показ заново.")
                }
            }
            delay(5000)
        }
    }

    fun requestScreenPermission(): Long? {
        if (!state.value.connected || state.value.reconnecting || state.value.busySharing || state.value.sharing) return null
        if (state.value.presenterId != null && state.value.presenterId != identity) {
            error("Сначала партнёр должен остановить свой показ"); return null
        }
        val requestId = capture.request()
        activeCaptureId = requestId
        permissionSession = generation
        mutable.update { it.copy(busySharing = true, error = null) }
        return requestId
    }

    @SuppressLint("MissingPermission") // Permission is checked again before playback capture.
    fun screenPermissionResult(data: Intent?, requestId: Long?) {
        if (requestId == null || permissionSession != generation || requestId != activeCaptureId) return
        if (!capture.permissionResult(data != null, requestId)) {
            capture.stopped(requestId); activeCaptureId = null
            mutable.update { it.copy(busySharing = false) }; return
        }
        val permissionData = data ?: return
        if (state.value.reconnecting) { stopSharing(); return }
        val rtc = room ?: run { capture.stopped(requestId); activeCaptureId = null; mutable.update { it.copy(busySharing = false) }; return }
        val invite = state.value.invite ?: return
        val attempt = generation
        val lease = PresenterLease()
        presenterLease = lease
        captureJob = scope.launch {
            captureLock.withLock {
                try {
                    check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    val requestStarted = SystemClock.elapsedRealtime()
                    api.presenter(invite, identity, "claim")
                    check(generation == attempt && room === rtc && requestId == activeCaptureId)
                    check(lease.granted(requestStarted, SystemClock.elapsedRealtime()))
                    check(rtc.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(permissionData,
                        notificationId = 102, notification = CallService.notification(app, true),
                        onStop = { stopSharing(requestId, rtc) })))
                    check(generation == attempt && room === rtc && requestId == activeCaptureId && lease.isValid(SystemClock.elapsedRealtime()))
                    val track = rtc.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack
                    val projection = (track?.capturer as? ScreenCapturerAndroid)?.mediaProjection
                        ?: throw IllegalStateException("Не удалось захватить экран")
                    val capturer = ScreenAudioCapturer(projection) { builder ->
                        builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                        builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        builder.excludeUid(Process.myUid())
                    }
                    capturer.gain = 0.7f
                    mixer.attach(capturer)
                    check(capture.started(requestId))
                    mutable.update { it.copy(sharing = capture.sharing, busySharing = false, presenterId = identity) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    if (generation == attempt && room === rtc && requestId == activeCaptureId) {
                        stopCaptureLocked(rtc, invite, requestId)
                        error("Не удалось начать показ. Возможно, партнёр уже показывает экран. Попробуй снова.")
                    }
                }
            }
        }
    }

    fun stopSharing() { stopSharing(activeCaptureId, room) }
    private fun stopSharing(requestId: Long?, rtc: Room?) {
        val invite = state.value.invite
        scope.launch {
            captureLock.withLock {
                if (requestId != null && requestId == activeCaptureId && room === rtc) stopCaptureLocked(rtc, invite, requestId)
            }
        }
    }
    private suspend fun stopCaptureLocked(rtc: Room?, invite: Invitation?, requestId: Long) {
        if (!capture.stopped(requestId)) return
        activeCaptureId = null
        presenterLease = null
        mixer.attach(null)
        mutable.update { it.copy(sharing = false, busySharing = false, playbackSignal = false,
            presenterId = if (it.presenterId == identity) null else it.presenterId) }
        runCatching { rtc?.localParticipant?.setScreenShareEnabled(false) }
        if (invite != null) runCatching { api.presenter(invite, identity, "release") }
    }

    fun toggleMicrophone() {
        audio.microphoneEnabled = !audio.microphoneEnabled
        mutable.update { it.copy(microphone = audio.microphoneEnabled) }
    }
    fun togglePlayback() {
        audio.playbackEnabled = !audio.playbackEnabled
        mutable.update { it.copy(playback = audio.playbackEnabled) }
    }
    fun sendMessage(text: String) {
        val content = text.trim()
        val rtc = room ?: return
        val attempt = generation
        if (!state.value.connected || content.isEmpty() || content.length > 2000) return
        scope.launch {
            val packet = ChatWire(UUID.randomUUID().toString(), content)
            try {
                rtc.localParticipant.publishData(json.encodeToString(packet).toByteArray(), topic = "ryadom.chat").getOrThrow()
                if (generation == attempt && room === rtc) addMessage(ChatMessage(packet.id, savedName, content, true))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (generation == attempt && room === rtc) error("Сообщение не отправлено. Проверь соединение.") }
        }
    }
    private fun addMessage(message: ChatMessage) {
        mutable.update { if (it.messages.any { old -> old.id == message.id }) it else it.copy(messages = (it.messages + message).takeLast(100)) }
    }

    fun disconnect() {
        ++generation
        connectionJob?.cancel(); connectionJob = null
        pollJob?.cancel(); pollJob = null
        eventsJob?.cancel(); eventsJob = null
        captureJob?.cancel(); captureJob = null
        watchdogJob?.cancel(); watchdogJob = null
        val invite = state.value.invite
        capture.stopped(); activeCaptureId = null; presenterLease = null; mixer.attach(null)
        val rtc = room; room = null
        runCatching { (rtc?.localParticipant?.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)?.setAudioBufferCallback(null) }
        runCatching { rtc?.disconnect() }
        runCatching { rtc?.release() }
        mutable.value = CallUiState()
        if (invite != null) scope.launch { captureLock.withLock { runCatching { api.presenter(invite, identity, "release") } } }
    }
}
