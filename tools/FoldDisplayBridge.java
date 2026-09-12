import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;

/** Shell-only, device-local bridge. Never writes global settings or holds a wakelock. */
public final class FoldDisplayBridge {
    static final class Policy {
        float angle = Float.NaN;
        int sessionState = -1;
        void sample(float value, long now) {
            if (Float.isFinite(value) && value >= 0 && value <= 180) angle = value;
        }
        int desiredState(boolean enabled, boolean interactive) {
            if (!enabled) {
                sessionState = -1;
                return -1;
            }
            if (!interactive) return -1;
            // Pin the primary for this opt-in session. Switching 4 <-> 5 during
            // a fold blanks both panels on Samsung, defeating prelighting.
            if (sessionState == -1) sessionState = angle == 0 ? 5 : 4;
            return sessionState;
        }
    }

    private final Object manager;
    private Object request;
    private final Class<?> requestClass;
    private final Method submit;
    private final Method cancel;
    private final Object callback;
    private int heldState = -1;
    private long retryAfter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FoldDisplayBridge() throws Exception {
        Class<?> global = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
        manager = global.getMethod("getInstance").invoke(null);
        requestClass = Class.forName("android.hardware.devicestate.DeviceStateRequest");
        Class<?> callbackClass = Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        submit = global.getMethod("requestState", requestClass, java.util.concurrent.Executor.class, callbackClass);
        cancel = global.getMethod("cancelStateRequest");
        callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == arguments[0];
                        return "FoldDisplayBridge.Callback";
                    }
                    if (method.getName().equals("onRequestCanceled") && arguments[0] == request) {
                        heldState = -1;
                        retryAfter = SystemClock.elapsedRealtime() + 250;
                    }
                    System.out.println(SystemClock.elapsedRealtime() + " " + method.getName());
                    return null;
                });
    }

    private synchronized void setState(int state) {
        if (state == heldState || (state != -1 && SystemClock.elapsedRealtime() < retryAfter)) return;
        try {
            if (state != -1) {
                Object builder = requestClass.getMethod("newBuilder", int.class).invoke(null, state);
                request = builder.getClass().getMethod("build").invoke(builder);
                submit.invoke(manager, request, (java.util.concurrent.Executor) handler::post, callback);
            } else {
                request = null;
                cancel.invoke(manager);
            }
            heldState = state;
            System.out.println(SystemClock.elapsedRealtime() + " requested=" + state);
        } catch (Exception e) {
            // Binder death releases any surviving request.
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (!Build.MODEL.startsWith("SM-F966") || Build.VERSION.SDK_INT != 36)
            throw new IllegalStateException("This bridge is verified only for Fold7 Android 16");
        // Prevent two bridge processes from competing for the override.
        RandomAccessFile lockFile = new RandomAccessFile("/data/local/tmp/duo-fold-bridge.lock", "rw");
        FileLock lock = lockFile.getChannel().tryLock();
        if (lock == null) throw new IllegalStateException("Bridge already running");
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        FoldDisplayBridge bridge = new FoldDisplayBridge();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> bridge.setState(-1)));
        if (args.length == 1 && args[0].equals("--probe")) {
            bridge.setState(4);
            Thread.sleep(1500);
            bridge.setState(-1);
            System.out.println("probe passed");
            System.exit(0);
        }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge == null) throw new IllegalStateException("No hinge sensor");
        Handler handler = new Handler(Looper.getMainLooper());
        Policy policy = new Policy();
        AtomicBoolean enabled = new AtomicBoolean(false);
        // A shell process cannot acquire providers through a system Context.
        // Query via the shell command on a worker, never on the hinge/IPC thread.
        new Thread(() -> {
            while (true) {
                try {
                    Process query = new ProcessBuilder("content", "query", "--user",
                            String.valueOf(Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null)), "--uri",
                            "content://com.duoopen.fold7.displaybridge/status").redirectErrorStream(true).start();
                    boolean found = false;
                    if (query.waitFor(2, TimeUnit.SECONDS) && query.exitValue() == 0) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(query.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.matches("Row: [0-9]+ enabled=1")) found = true;
                            }
                        }
                    } else query.destroyForcibly();
                    enabled.set(found);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    enabled.set(false);
                    System.err.println("Cannot read display test gate; exiting");
                    System.exit(1);
                    return;
                }
            }
        }, "duo-accessibility-gate").start();
        SensorEventListener listener = new SensorEventListener() {
            public void onSensorChanged(SensorEvent event) {
                long now = SystemClock.elapsedRealtime();
                policy.sample(event.values[0], now);
                System.out.println(now + " angle=" + policy.angle + " gate=" + enabled.get() + " interactive=" + power.isInteractive());
                bridge.setState(policy.desiredState(enabled.get(), power.isInteractive()));
            }
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        if (!sensors.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_GAME, handler))
            throw new IllegalStateException("Cannot subscribe to hinge sensor");
        handler.post(new Runnable() {
            public void run() {
                bridge.setState(policy.desiredState(enabled.get(), power.isInteractive()));
                handler.postDelayed(this, 100);
            }
        });
        System.out.println("ready: " + hinge.getName());
        Looper.loop();
        sensors.unregisterListener(listener);
        bridge.setState(-1);
        lock.release();
        lockFile.close();
    }

    static void selfTest() {
        Policy p = new Policy();
        check(p.desiredState(false, true) == -1, "disabled releases");
        p.sample(180, 0);
        check(p.desiredState(true, true) == 4, "open session prelights cover");
        p.sample(90, 1);
        check(p.desiredState(true, true) == 4, "partial keeps same mode");
        p.sample(0, 2);
        check(p.desiredState(true, true) == 4, "closed cannot blank panels through a mode swap");
        check(p.desiredState(true, false) == -1, "sleep releases");
        check(p.desiredState(true, true) == 4, "wake preserves session primary");
        check(p.desiredState(false, true) == -1, "opt-out releases and resets session");
        check(p.desiredState(true, true) == 5, "new closed session prelights inner");
        p.sample(180, 3);
        check(p.desiredState(true, true) == 5, "opening preserves cover-primary session");
        p.sample(Float.NaN, 4);
        check(p.desiredState(true, true) == 5, "invalid sample cannot change mode");
        System.out.println("10 policy checks passed");
    }
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
