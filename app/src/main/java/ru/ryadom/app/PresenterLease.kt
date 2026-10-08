package ru.ryadom.app

/** Conservative local deadline, measured from request start, independent of HTTP completion. */
class PresenterLease {
    var hasGrant = false; private set
    private var deadline = 0L
    fun granted(requestStartedAt: Long, now: Long): Boolean {
        deadline = requestStartedAt + 15_000
        hasGrant = now < deadline
        return hasGrant
    }
    fun isValid(now: Long): Boolean = hasGrant && now < deadline
}
