package com.duoopen.shell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.SurfaceControl
import com.duoopen.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * App-side handle on Shizuku and on [DuoShellService]. Optional: everything
 * degrades to the accessibility screenshot and the public hinge sensor when
 * Shizuku isn't installed, running or authorised.
 */
object ShizukuBridge {
    sealed class State(val summary: String) {
        object NotInstalled : State("Shizuku isn't installed.")
        object NotRunning : State("Shizuku is installed but not running. Start it (wireless debugging or a computer), then come back.")
        object NeedsPermission : State("Shizuku is running. Authorise Duo Open to use it.")
        object Denied : State("Shizuku access was declined. Enable Duo Open under Shizuku → Authorised applications.")
        class Ready(val uid: Int, val capture: String?) : State(
            if (capture == null || capture.startsWith("unavailable")) "Shizuku ready (uid $uid); display capture ${capture ?: "unknown"}."
            else "Shizuku ready (uid $uid); fast display capture via $capture.",
        )
    }

    private const val TAG = "DuoShizuku"
    private const val REQUEST_CODE = 7311

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var service: IBinder? = null
        private set
    private var binding = false
    private lateinit var appContext: Context

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            binding = false
            Log.i(TAG, "shell service connected")
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            Log.i(TAG, "shell service disconnected")
            refresh()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { service = null; refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        refresh()
        if (result == PackageManager.PERMISSION_GRANTED) bind()
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure { Log.w(TAG, "Shizuku listeners", it) }
        refresh()
    }

    val installed: Boolean
        get() = runCatching { appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)

    val ready: Boolean get() = state.value is State.Ready && service != null

    fun refresh() {
        val next: State = runCatching {
            when {
                !installed && !Shizuku.pingBinder() -> State.NotInstalled
                !Shizuku.pingBinder() -> State.NotRunning
                Shizuku.isPreV11() -> State.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    if (service == null) bind()
                    State.Ready(Shizuku.getUid(), ping()?.getString("capture"))
                }
                Shizuku.shouldShowRequestPermissionRationale() -> State.Denied
                else -> State.NeedsPermission
            }
        }.getOrElse { State.NotRunning }
        _state.value = next
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure { Log.w(TAG, "requestPermission", it) }
        refresh()
    }

    private fun args() = Shizuku.UserServiceArgs(ComponentName(appContext, DuoShellService::class.java))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    fun bind() {
        if (service != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args(), connection) }
            .onFailure { binding = false; Log.w(TAG, "bindUserService", it) }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args(), connection, true) }
        service = null
    }

    private fun call(code: Int, write: (Parcel) -> Unit = {}): Bundle? {
        val binder = service ?: return null
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            p.writeInterfaceToken(ShellProtocol.TOKEN)
            write(p)
            if (!binder.transact(code, p, r, 0)) return null
            r.readException()
            if (r.dataAvail() > 0) r.readBundle(ShizukuBridge::class.java.classLoader) else Bundle()
        } catch (e: Exception) {
            Log.w(TAG, "shell call $code failed: $e")
            null
        } finally {
            p.recycle()
            r.recycle()
        }
    }

    fun ping(): Bundle? = call(ShellProtocol.PING)

    /** Blocking; call off the main thread. Null if unavailable or the frame had secure content. */
    fun capture(displayId: Int, excluded: List<SurfaceControl>, scale: Float): Bitmap? {
        val b = call(ShellProtocol.CAPTURE) { p ->
            p.writeInt(displayId)
            p.writeInt(excluded.size)
            excluded.forEach { p.writeTypedObject(it, 0) }
            p.writeFloat(scale)
        } ?: return null
        if (!b.getBoolean("ok")) {
            Log.w(TAG, "shell capture failed: ${b.getString("error")}")
            return null
        }
        if (b.getBoolean("secure")) return null
        @Suppress("DEPRECATION")
        return b.getParcelable("bitmap")
    }

    /** Receives angles from the shell-side wallpaper log reader. */
    private class AngleCallback(private val onAngle: (Float) -> Unit) : Binder() {
        init { attachInterface(null, ShellProtocol.CALLBACK_TOKEN) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ShellProtocol.CB_ANGLE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(ShellProtocol.CALLBACK_TOKEN)
            onAngle(data.readFloat())
            return true
        }
    }

    private var angleCallback: AngleCallback? = null

    fun startAngles(action: String, onAngle: (Float) -> Unit): Boolean {
        val cb = AngleCallback(onAngle)
        angleCallback = cb
        return call(ShellProtocol.START_ANGLES) { p ->
            p.writeString(action)
            p.writeStrongBinder(cb)
        } != null
    }

    fun stopAngles() {
        call(ShellProtocol.STOP_ANGLES)
        angleCallback = null
    }

    fun angleStatus(): Bundle? = call(ShellProtocol.ANGLE_STATUS)
}
