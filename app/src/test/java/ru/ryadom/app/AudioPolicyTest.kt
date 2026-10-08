package ru.ryadom.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AudioPolicyTest {
    @Test fun muteMicrophoneClearsOnlyValidInputAndKeepsPlaybackEnabled() {
        val policy = AudioPolicy()
        policy.microphoneEnabled = false
        val buffer = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50, 60))
        buffer.position(2)
        policy.prepareMicrophone(buffer, 4)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 50, 60), buffer.array())
        assertEquals(2, buffer.position())
        assertTrue(policy.playbackEnabled)
    }
    @Test fun mediaMuteDoesNotMuteMicrophone() {
        val policy = AudioPolicy()
        policy.playbackEnabled = false
        val buffer = ByteBuffer.wrap(byteArrayOf(11, 22))
        policy.prepareMicrophone(buffer, 2)
        assertArrayEquals(byteArrayOf(11, 22), buffer.array())
        assertTrue(policy.microphoneEnabled)
    }
}
