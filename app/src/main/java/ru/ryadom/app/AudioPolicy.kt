package ru.ryadom.app

import java.nio.ByteBuffer

class AudioPolicy {
    @Volatile var microphoneEnabled = true
    @Volatile var playbackEnabled = true
    fun prepareMicrophone(buffer: ByteBuffer, bytesRead: Int) {
        if (!microphoneEnabled) {
            for (index in 0 until bytesRead.coerceIn(0, buffer.limit())) buffer.put(index, 0)
        }
    }
}
