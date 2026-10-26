package io.github.burakgon.stop.daemon

import android.content.Context
import android.os.Parcel
import io.github.burakgon.stop.BuildConfig
import kotlin.system.exitProcess

/**
 * Entry point of the Shizuku user service. Shizuku instantiates this class by name inside a
 * process running as shell (adb) or root, so it must keep a public no-arg or Context constructor.
 */
class StopDaemonService() : IStopDaemon.Stub() {

    /** Shizuku prefers this constructor when it can create a context for us. */
    @Suppress("unused")
    constructor(context: Context) : this()

    private val daemon = Daemon(BuildConfig.APPLICATION_ID).also { it.start() }

    override fun destroy() {
        daemon.shutdown()
        exitProcess(0)
    }

    override fun getInfo(): String = daemon.info()

    override fun applyConfig(config: String) = daemon.applyConfig(config)

    override fun setListener(listener: IDaemonListener?) = daemon.setListener(listener)

    override fun getEvents(afterId: Long): String = daemon.events(afterId)

    override fun getRunningBlocked(): String = daemon.runningBlocked()

    override fun stopNow(packages: Array<String>?): Int = daemon.stopNow(packages?.toList().orEmpty())

    override fun stopAllBlocked(): Int = daemon.stopAll()

    override fun resetRestrictions() = daemon.resetRestrictions()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Only Stop itself (and Shizuku, which runs as shell or root) may drive the daemon.
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && !daemon.isTrustedCaller(getCallingUid())) {
            throw SecurityException("uid ${getCallingUid()} may not use the Stop daemon")
        }
        return super.onTransact(code, data, reply, flags)
    }
}
