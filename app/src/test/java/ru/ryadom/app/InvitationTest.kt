package ru.ryadom.app

import org.junit.Assert.*
import org.junit.Test

class InvitationTest {
    @Test fun invitationRoundTripsWithoutLosingSecrets() {
        val invitation = Invitation("https://ryadom.example.ts.net:8443", "a-very-long-private-invite-key", "room-123")
        assertEquals(invitation, Invitation.parse(invitation.toLink()))
    }
    @Test fun setupAndRoomLinksAreDistinct() {
        val setup = Invitation("https://ryadom.example.ts.net:8443", "a-very-long-installation-key")
        assertTrue(requireNotNull(Invitation.parse(setup.toLink())).isSetup)
        assertFalse(requireNotNull(Invitation.parse(setup.copy(roomId = "abc").toLink())).isSetup)
    }
    @Test fun rejectsMalformedAndInsecureDestinations() {
        val invalid = listOf("", "https://example.org", "ryadom://join?server=http%3A%2F%2Fexample.ts.net&key=abcdefgh&room=x", "ryadom://join?server=%&key=x", "ryadom://join?server=https%3A%2F%2Fuser%3Apass%40example.ts.net&key=aaaaaaaaaaaaaaaaaaaaaaaa&room=x", "ryadom://join?server=https%3A%2F%2Fexample.ts.net&key=aaaaaaaaaaaaaaaaaaaaaaaa&room=x&room=y")
        invalid.forEach { assertNull(it, Invitation.parse(it)) }
    }
}
