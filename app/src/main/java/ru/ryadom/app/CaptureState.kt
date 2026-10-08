package ru.ryadom.app

class CaptureState {
    var sharing = false; private set
    var awaitingPermission = false; private set
    private var permissionGranted = false
    private var generation = 0L
    fun request(): Long {
        if (!sharing) { ++generation; awaitingPermission = true; permissionGranted = false }
        return generation
    }
    fun permissionResult(granted: Boolean, requestId: Long = generation): Boolean {
        if (requestId != generation) return false
        permissionGranted = awaitingPermission && granted
        awaitingPermission = false
        return permissionGranted
    }
    fun started(requestId: Long = generation): Boolean {
        if (requestId != generation) return false
        sharing = permissionGranted
        return sharing
    }
    fun stopped(requestId: Long? = null): Boolean {
        if (requestId != null && requestId != generation) return false
        ++generation
        sharing = false; awaitingPermission = false; permissionGranted = false
        return true
    }
}
