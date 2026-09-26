package com.duoopen.shell

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceControl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.ObjIntConsumer
import java.util.regex.Pattern

/**
 * Runs in a process Shizuku spawns with ADB (shell) privileges. Two jobs the
 * app process can't do itself:
 *
 * 1. **Display capture without the accessibility rate limit**, through the
 *    hidden `IWindowManager.captureDisplay`, with our own overlay layers
 *    excluded — so the fold can re-capture the live screen while it plays.
 * 2. **Samsung's continuous hinge angle.** Only Samsung's own components get
 *    the real angle; its "Fold interactive" home wallpaper logs it on every
 *    wallpaper command it receives (`mCurrentAngle=…`), and the shell user may
 *    read logcat. The app pings the wallpaper; this tails the log and calls
 *    back with each fresh value.
 *
 * Both techniques were worked out by Duo Fold Live
 * (github.com/joeconsorti/duo-fold-live, MIT) — the capture-API resolution
 * and the wallpaper log format in particular follow their findings.
 *
 * Nothing here is reachable without the user authorising this app in
 * Shizuku, and every transaction checks the calling uid.
 */
class DuoShellService : Binder() {

    private var owner = -1
    private var captureApi: CaptureApi? = null
    private var reader: AngleReader? = null

    init {
        attachInterface(null, ShellProtocol.TOKEN)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(ShellProtocol.TOKEN)
            return true
        }
        if (code == SHIZUKU_DESTROY) {
            reader?.stop()
            System.exit(0)
            return true
        }
        data.enforceInterface(ShellProtocol.TOKEN)
        val caller = getCallingUid()
        if (owner < 0) owner = caller
        if (caller != owner) throw SecurityException("wrong caller")
        val out = reply ?: return false
        when (code) {
            ShellProtocol.PING -> {
                out.writeNoException()
                out.writeBundle(Bundle().apply {
                    putInt("uid", Process.myUid())
                    putInt("pid", Process.myPid())
                    putString("capture", runCatching { api().name }.getOrElse { "unavailable: ${it.message}" })
                })
            }
            ShellProtocol.CAPTURE -> {
                val displayId = data.readInt()
                val n = data.readInt()
                val excluded = Array(n) { data.readTypedObject(SurfaceControl.CREATOR) }
                val scale = data.readFloat()
                val identity = clearCallingIdentity()
                val result = try {
                    capture(displayId, excluded.filterNotNull().toTypedArray(), scale)
                } catch (t: Throwable) {
                    var c: Throwable = t
                    while (c.cause != null) c = c.cause!!
                    Bundle().apply { putString("error", "${c.javaClass.simpleName}: ${c.message}") }
                } finally {
                    excluded.forEach { runCatching { it?.release() } }
                    restoreCallingIdentity(identity)
                }
                out.writeNoException()
                out.writeBundle(result)
            }
            ShellProtocol.START_ANGLES -> {
                val action = data.readString() ?: throw IllegalArgumentException("action")
                val callback = data.readStrongBinder() ?: throw IllegalArgumentException("callback")
                reader?.stop()
                reader = AngleReader(action, callback).also { it.start() }
                out.writeNoException()
            }
            ShellProtocol.STOP_ANGLES -> {
                reader?.stop()
                reader = null
                out.writeNoException()
            }
            ShellProtocol.ANGLE_STATUS -> {
                out.writeNoException()
                out.writeBundle(reader?.status() ?: Bundle().apply { putString("state", "not started") })
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    // ---- capture -----------------------------------------------------------

    private class CaptureApi(
        val name: String,
        val builderCtor: Constructor<*>,
        val builder: Class<*>,
        val listenerCtor: Constructor<*>,
        val statusCallback: Boolean,
        val captureDisplay: Method,
    )

    private fun systemService(name: String, stub: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder
        return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Finds whichever generation of the hidden capture API this Android has:
     * `android.window.ScreenCaptureInternal` (status callback) or
     * `android.window.ScreenCapture` (plain consumer), driven through
     * `IWindowManager.captureDisplay`.
     */
    private fun api(): CaptureApi {
        captureApi?.let { return it }
        val wm = Class.forName("android.view.IWindowManager")
        val errors = StringBuilder()
        for (family in FAMILIES) {
            try {
                val args = Class.forName("$family\$CaptureArgs")
                val builder = Class.forName("$family\$CaptureArgs\$Builder")
                val listener = Class.forName("$family\$ScreenCaptureListener")
                val ctor = builder.getConstructor()
                builder.getMethod("setSourceCrop", Rect::class.java)
                builder.getMethod("setFrameScale", java.lang.Float.TYPE)
                builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                builder.getMethod("build")
                var status = true
                val lctor = try {
                    listener.getConstructor(ObjIntConsumer::class.java)
                } catch (e: NoSuchMethodException) {
                    status = false
                    listener.getConstructor(Consumer::class.java)
                }
                val capture = wm.getMethod("captureDisplay", Integer.TYPE, args, listener)
                return CaptureApi(family, ctor, builder, lctor, status, capture).also { captureApi = it }
            } catch (e: ReflectiveOperationException) {
                errors.append(family).append(": ").append(e).append("; ")
            }
        }
        throw ClassNotFoundException("no compatible display capture API: $errors")
    }

    private fun capture(displayId: Int, excluded: Array<SurfaceControl>, scale: Float): Bundle {
        val t0 = SystemClock.elapsedRealtime()
        val api = api()
        val wm = systemService("window", "android.view.IWindowManager\$Stub")
        val dm = systemService("display", "android.hardware.display.IDisplayManager\$Stub")
        val info = Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo", Integer.TYPE)
            .invoke(dm, displayId) ?: throw IllegalStateException("no display $displayId")
        val w = info.javaClass.getField("logicalWidth").getInt(info)
        val h = info.javaClass.getField("logicalHeight").getInt(info)
        if (w <= 0 || h <= 0) throw IllegalStateException("display $displayId has no size")

        val b = api.builderCtor.newInstance()
        api.builder.getMethod("setSourceCrop", Rect::class.java).invoke(b, Rect(0, 0, w, h))
        api.builder.getMethod("setFrameScale", java.lang.Float.TYPE).invoke(b, scale.coerceIn(0.1f, 1f))
        if (excluded.isNotEmpty()) {
            api.builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java).invoke(b, excluded)
        }
        val args = api.builder.getMethod("build").invoke(b)

        val latch = CountDownLatch(1)
        var shot: Any? = null
        val callback: Any = if (api.statusCallback) {
            ObjIntConsumer<Any?> { s, _ -> shot = s; latch.countDown() }
        } else {
            Consumer<Any?> { s -> shot = s; latch.countDown() }
        }
        val listener = api.listenerCtor.newInstance(callback)
        api.captureDisplay.invoke(wm, displayId, args, listener)
        if (!latch.await(400, TimeUnit.MILLISECONDS)) throw IllegalStateException("capture timed out")
        java.lang.ref.Reference.reachabilityFence(callback)
        java.lang.ref.Reference.reachabilityFence(listener)
        val result = shot ?: throw IllegalStateException("no frame")

        var buffer: HardwareBuffer? = null
        try {
            buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as? HardwareBuffer
            val secure = runCatching {
                result.javaClass.getMethod("containsSecureLayers").invoke(result) as Boolean
            }.getOrDefault(false)
            val hardware = result.javaClass.getMethod("asBitmap").invoke(result) as? Bitmap
                ?: throw IllegalStateException("frame not readable")
            val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            return Bundle().apply {
                putBoolean("ok", true)
                putParcelable("bitmap", bitmap)
                putInt("width", w)
                putInt("height", h)
                putBoolean("secure", secure)
                putLong("ms", SystemClock.elapsedRealtime() - t0)
            }
        } finally {
            buffer?.close()
        }
    }

    // ---- Samsung wallpaper angle reader -------------------------------------

    /**
     * Tails logcat for the "Fold interactive" wallpaper's command log. Each
     * command the app sends makes the wallpaper log a line containing the
     * action it was sent, whether it's visible, and `mCurrentAngle=<deg>`.
     * Log format per Duo Fold Live's findings.
     */
    private class AngleReader(private val action: String, private val callback: IBinder) {
        @Volatile private var process: java.lang.Process? = null
        @Volatile private var stopped = false
        @Volatile private var state = "starting"
        private var lines = 0
        private var parsed = 0
        private var rejected = 0
        private var lastAngle = Float.NaN
        private var lastUptime = 0L

        fun start() {
            val thread = Thread({
                var child: java.lang.Process? = null
                try {
                    child = ProcessBuilder(
                        "logcat", "-v", "epoch", "-T", "1", "-s", "SprWallpaper|FoldInteractive:V", "*:S",
                    ).redirectErrorStream(true).start()
                    process = child
                    state = "listening"
                    BufferedReader(InputStreamReader(child.inputStream)).use { input ->
                        while (!stopped) {
                            val line = input.readLine() ?: break
                            lines++
                            val value = parse(line) ?: continue
                            // Never treat a buffered line as current.
                            val epoch = line.trim().split(Regex("\\s+"), 2).firstOrNull()?.toDoubleOrNull()
                            val age = if (epoch == null) Long.MAX_VALUE else System.currentTimeMillis() - (epoch * 1000).toLong()
                            if (age < -100 || age > 1500) {
                                rejected++
                                continue
                            }
                            parsed++
                            lastAngle = value
                            lastUptime = SystemClock.uptimeMillis() - age.coerceAtLeast(0)
                            state = "receiving"
                            val p = Parcel.obtain()
                            try {
                                p.writeInterfaceToken(ShellProtocol.CALLBACK_TOKEN)
                                p.writeFloat(value)
                                p.writeLong(lastUptime)
                                callback.transact(ShellProtocol.CB_ANGLE, p, null, IBinder.FLAG_ONEWAY)
                            } catch (e: Exception) {
                                state = "callback gone: ${e.message}"
                                break
                            } finally {
                                p.recycle()
                            }
                        }
                    }
                    if (!stopped) state = "log reader ended"
                } catch (e: Exception) {
                    state = "reader error: $e"
                } finally {
                    child?.destroy()
                }
            }, "duo-angle-reader")
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            stopped = true
            process?.destroy()
            state = "stopped"
        }

        fun status(): Bundle = Bundle().apply {
            putString("state", state)
            putInt("lines", lines)
            putInt("parsed", parsed)
            putInt("rejected", rejected)
            putFloat("angle", lastAngle)
            putLong("last", lastUptime)
        }

        private fun parse(line: String): Float? {
            if (!line.contains("SprWallpaper|FoldInteractive") || !line.contains("onCommand:")) return null
            if (!(line.contains("action=$action,") || line.contains("action[$action]"))) return null
            if (!(line.contains("isVisible=true") || line.contains("isVisible[true]"))) return null
            val m = ANGLE.matcher(line)
            if (!m.find()) return null
            val v = m.group(1)?.toFloatOrNull() ?: return null
            return if (v in 0f..180f) v else null
        }

        private companion object {
            val ANGLE: Pattern = Pattern.compile("mCurrentAngle(?:=|\\[)([0-9]+(?:\\.[0-9]+)?)")
        }
    }

    private companion object {
        /** Shizuku asks user services to exit with this code. */
        const val SHIZUKU_DESTROY = 16777115
        val FAMILIES = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
    }
}
