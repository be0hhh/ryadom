package ru.ryadom.app

import org.junit.Assert.*
import org.junit.Test

class CaptureStateTest {
    @Test fun stalePermissionAndStopCallbacksCannotAffectNewCapture() {
        val state = CaptureState()
        val old = state.request()
        state.stopped()
        val fresh = state.request()
        assertFalse(state.permissionResult(true, old))
        assertTrue(state.awaitingPermission)
        assertTrue(state.permissionResult(true, fresh))
        assertTrue(state.started(fresh))
        assertFalse(state.stopped(old))
        assertTrue(state.sharing)
        assertTrue(state.stopped(fresh))
        assertFalse(state.sharing)
    }
    @Test fun doesNotShareBeforePermissionAndPublication() {
        val state = CaptureState()
        state.request()
        assertTrue(state.awaitingPermission)
        assertFalse(state.sharing)
        state.permissionResult(true)
        assertFalse(state.sharing)
        state.started()
        assertTrue(state.sharing)
    }
    @Test fun refusedPermissionCannotStartSharing() {
        val state = CaptureState()
        state.request()
        state.permissionResult(false)
        state.started()
        assertFalse(state.sharing)
        assertFalse(state.awaitingPermission)
    }
    @Test fun revokedProjectionRequiresFreshPermission() {
        val state = CaptureState()
        state.request(); state.permissionResult(true); state.started(); state.stopped()
        assertFalse(state.sharing)
        state.started()
        assertFalse(state.sharing)
        state.request(); state.permissionResult(true); state.started()
        assertTrue(state.sharing)
    }
}
