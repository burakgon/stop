package io.github.burakgon.stop.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.burakgon.stop.BuildConfig
import io.github.burakgon.stop.daemon.DaemonInfo
import io.github.burakgon.stop.daemon.IDaemonListener
import io.github.burakgon.stop.daemon.IStopDaemon
import io.github.burakgon.stop.daemon.StopDaemonService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Starts the privileged daemon as a Shizuku user service and keeps a connection to it.
 * The daemon outlives the app's process, so binding again later reconnects to the same instance.
 * All connection state is changed on the main thread.
 */
class DaemonClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shizuku: ShizukuMonitor,
) {
    sealed interface State {
        /** Shizuku isn't ready, so there is nothing to connect to. */
        data object Unavailable : State
        data object Connecting : State
        data class Connected(val info: DaemonInfo) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _service = MutableStateFlow<IStopDaemon?>(null)
    val service: StateFlow<IStopDaemon?> = _service.asStateFlow()

    var listener: IDaemonListener? = null

    private val main = Handler(Looper.getMainLooper())
    private var binding = false

    /** Bumped on every (dis)connection, so late results from an older attempt are dropped. */
    private var generation = 0
    private var failures = 0
    private var connectedAt = 0L

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, StopDaemonService::class.java.name))
        .daemon(true)
        .processNameSuffix("daemon")
        .tag("stop-daemon")
        .debuggable(BuildConfig.DEBUG)
        .version(installVersion(context))

    private val reconnect = Runnable { connect() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val attempt = ++generation
            if (binder == null || !binder.pingBinder()) {
                onFailed(attempt, "The daemon did not start")
                return
            }
            val daemon = IStopDaemon.Stub.asInterface(binder)
            try {
                binder.linkToDeath({ main.post { onDaemonGone(attempt) } }, 0)
            } catch (_: Exception) {
                onFailed(attempt, "The daemon died while starting")
                return
            }
            scope.launch(Dispatchers.IO) {
                val info = try {
                    listener?.let(daemon::setListener)
                    DaemonInfo.fromJson(daemon.info)
                } catch (e: Exception) {
                    Log.w(TAG, "daemon handshake failed", e)
                    main.post { onFailed(attempt, e.message ?: e.javaClass.simpleName) }
                    return@launch
                }
                main.post {
                    if (attempt != generation || !binder.isBinderAlive) return@post
                    binding = false
                    connectedAt = SystemClock.elapsedRealtime()
                    _service.value = daemon
                    _state.value = State.Connected(info)
                    Log.i(TAG, "connected to daemon pid ${info.pid} uid ${info.uid}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = onDaemonGone(generation)
    }

    fun start() {
        scope.launch {
            shizuku.status.collect { status ->
                if (status is ShizukuMonitor.Status.Ready) {
                    connect()
                } else {
                    main.post {
                        generation++
                        binding = false
                        main.removeCallbacks(reconnect)
                        _service.value = null
                        _state.value = State.Unavailable
                    }
                }
            }
        }
    }

    fun connect() {
        main.post {
            if (binding || _service.value != null) return@post
            if (shizuku.status.value !is ShizukuMonitor.Status.Ready) return@post
            main.removeCallbacks(reconnect)
            try {
                binding = true
                _state.value = State.Connecting
                Shizuku.bindUserService(args, connection)
            } catch (e: Throwable) {
                Log.w(TAG, "bindUserService failed", e)
                onFailed(generation, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stops the daemon process entirely. */
    fun shutdown() {
        main.post {
            generation++
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (_: Throwable) {
            }
            binding = false
            main.removeCallbacks(reconnect)
            _service.value = null
            _state.value = State.Unavailable
        }
    }

    suspend fun refreshInfo() {
        val info = call { DaemonInfo.fromJson(it.info) } ?: return
        if (_service.value != null) _state.value = State.Connected(info)
    }

    /** Runs [block] against the daemon on a background thread; null if it isn't reachable. */
    suspend fun <T> call(block: (IStopDaemon) -> T): T? {
        val daemon = _service.value ?: return null
        return withContext(Dispatchers.IO) {
            try {
                block(daemon)
            } catch (e: Exception) {
                Log.w(TAG, "daemon call failed", e)
                null
            }
        }
    }

    suspend fun awaitService(timeoutMs: Long): IStopDaemon? {
        connect()
        return withTimeoutOrNull(timeoutMs) { service.filterNotNull().first() }
    }

    private fun onFailed(attempt: Int, message: String) {
        if (attempt != generation) return
        binding = false
        _service.value = null
        _state.value = State.Failed(message)
        scheduleReconnect()
    }

    private fun onDaemonGone(attempt: Int) {
        if (attempt != generation) return
        generation++
        binding = false
        _service.value = null
        // A daemon that kept running for a while counts as healthy again.
        if (connectedAt != 0L && SystemClock.elapsedRealtime() - connectedAt > STABLE_MS) failures = 0
        connectedAt = 0L
        if (shizuku.status.value is ShizukuMonitor.Status.Ready) {
            _state.value = State.Connecting
            scheduleReconnect()
        } else {
            _state.value = State.Unavailable
        }
    }

    /** Retries with growing pauses, so a daemon that keeps crashing can't spin the app. */
    private fun scheduleReconnect() {
        val delay = RECONNECT_DELAYS_MS[failures.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        failures++
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    private companion object {
        const val TAG = "Stop"
        const val STABLE_MS = 30_000L
        val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000, 60_000)

        /**
         * Shizuku replaces a running user service whose version differs. Deriving it from the
         * install time (not just the version code) also swaps the daemon after reinstalling the
         * same version, so it never keeps running code from an older APK.
         */
        fun installVersion(context: Context): Int {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            return ((installed / 1_000) % Int.MAX_VALUE).toInt()
        }
    }
}
