package ru.ryadom.app

import io.livekit.android.audio.MixerAudioBufferCallback
import io.livekit.android.audio.ScreenAudioCapturer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlaybackMixer(val policy: AudioPolicy) : MixerAudioBufferCallback() {
    private val capture = AtomicReference<ScreenAudioCapturer?>(null)
    val lastSignalAt = AtomicLong(0)

    fun attach(capturer: ScreenAudioCapturer?) {
        capture.getAndSet(capturer)?.releaseAudioResources()
        lastSignalAt.set(0)
    }

    override fun onBufferRequest(originalBuffer: ByteBuffer, audioFormat: Int, channelCount: Int,
        sampleRate: Int, bytesRead: Int, captureTimeNs: Long): BufferResponse? {
        policy.prepareMicrophone(originalBuffer, bytesRead)
        val response = capture.get()?.onBufferRequest(originalBuffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs)
        response?.byteBuffer?.let { buffer ->
            for (i in 0 until buffer.limit()) {
                if (buffer.get(i).toInt() != 0) { lastSignalAt.set(android.os.SystemClock.elapsedRealtime()); break }
            }
        }
        return if (policy.playbackEnabled) response else null
    }
}
