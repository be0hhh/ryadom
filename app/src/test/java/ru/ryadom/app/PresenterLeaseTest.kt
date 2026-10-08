package ru.ryadom.app

import org.junit.Assert.*
import org.junit.Test

class PresenterLeaseTest {
    @Test fun delayedHttpResponseDoesNotExtendServerLease() {
        val lease = PresenterLease()
        assertTrue(lease.granted(requestStartedAt = 1000, now = 12000))
        assertTrue(lease.isValid(15999))
        assertFalse(lease.isValid(16000))
    }
    @Test fun responseAfterLocalDeadlineCannotStartSharing() {
        val lease = PresenterLease()
        assertFalse(lease.granted(1000, 16000))
        assertFalse(lease.isValid(16000))
    }
    @Test fun freshRenewalExtendsDeadlineFromItsOwnRequestStart() {
        val lease = PresenterLease()
        assertTrue(lease.granted(1000, 1200))
        assertTrue(lease.granted(6000, 6500))
        assertTrue(lease.isValid(20999))
        assertFalse(lease.isValid(21000))
    }
}
