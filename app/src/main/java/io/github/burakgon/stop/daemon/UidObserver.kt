package io.github.burakgon.stop.daemon

import android.app.IUidObserver
import android.os.Parcel
import android.util.Log

/**
 * Receives uid process-state changes from the activity manager.
 *
 * The framework's IUidObserver changed signatures across releases. The device's own Stub
 * dispatches to whichever variant it was built with, so every known variant is declared here.
 * The extra overloads look unused to the compiler; R8 keeps them through proguard-rules.pro.
 */
internal class UidObserver(
    private val gone: Int,
    private val onState: (uid: Int, procState: Int) -> Unit,
) : IUidObserver.Stub() {

    override fun onUidStateChanged(uid: Int, procState: Int, procStateSeq: Long, capability: Int) {
        onState(uid, procState)
    }

    /** Android 9 and 10. */
    fun onUidStateChanged(uid: Int, procState: Int, procStateSeq: Long) {
        onState(uid, procState)
    }

    override fun onUidGone(uid: Int, disabled: Boolean) {
        onState(uid, gone)
    }

    override fun onUidActive(uid: Int) = Unit

    override fun onUidIdle(uid: Int, disabled: Boolean) = Unit

    override fun onUidCachedChanged(uid: Int, cached: Boolean) = Unit

    override fun onUidProcAdjChanged(uid: Int, adj: Int) = Unit

    /** Android 14. */
    fun onUidProcAdjChanged(uid: Int) = Unit

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = try {
        super.onTransact(code, data, reply, flags)
    } catch (t: Throwable) {
        // A vendor build may add a callback we don't implement; never let that kill the daemon.
        Log.w("StopDaemon", "uid observer transaction $code failed", t)
        true
    }
}
