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
import java.nio.channels.FileLock;

/** Shell-only, device-local bridge. Never writes global settings or holds a wakelock. */
public final class FoldDisplayBridge {
    static final class Policy {
        float angle = Float.NaN;
        float motionAngle = Float.NaN;
        long lastMotion = -1;
        void sample(float value, long now) {
            if (!Float.isFinite(value) || value < 0 || value > 180) {
                angle = Float.NaN;
                lastMotion = -1;
                return;
            }
            angle = value;
            if (Float.isNaN(motionAngle)) motionAngle = value;
            if (Math.abs(value - motionAngle) >= 0.5f) {
                lastMotion = now;
                motionAngle = value;
            }
        }
        boolean wantsConcurrent(long now, boolean interactive) {
            return interactive && angle > 3 && angle < 177 && lastMotion >= 0
                    && now - lastMotion < 1500;
        }
    }

    private final Object manager;
    private final Object request;
    private final Method submit;
    private final Method cancel;
    private boolean held;

    private FoldDisplayBridge() throws Exception {
        Class<?> global = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
        manager = global.getMethod("getInstance").invoke(null);
        Class<?> requestClass = Class.forName("android.hardware.devicestate.DeviceStateRequest");
        Object builder = requestClass.getMethod("newBuilder", int.class).invoke(null, 4);
        request = builder.getClass().getMethod("build").invoke(builder);
        submit = global.getMethod("requestState", requestClass, java.util.concurrent.Executor.class,
                Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback"));
        cancel = global.getMethod("cancelStateRequest");
    }

    private synchronized void setConcurrent(boolean enabled) {
        if (enabled == held) return;
        try {
            if (enabled) submit.invoke(manager, request, null, null);
            else cancel.invoke(manager);
            held = enabled;
            System.out.println(enabled ? "concurrent requested" : "physical state restored");
        } catch (Exception e) {
            // Exit on any IPC failure; binder death releases any surviving request.
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> bridge.setConcurrent(false)));
        if (args.length == 1 && args[0].equals("--probe")) {
            bridge.setConcurrent(true);
            Thread.sleep(1500);
            bridge.setConcurrent(false);
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
                    Process query = new ProcessBuilder("settings", "--user", "current", "get",
                            "secure", "enabled_accessibility_services").redirectErrorStream(true).start();
                    boolean found = false;
                    if (query.waitFor(2, TimeUnit.SECONDS) && query.exitValue() == 0) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(query.getInputStream()))) {
                            String services = reader.readLine();
                            if (services != null) for (String component : services.split(":")) {
                                if (component.equals("com.duoopen.fold7/com.duoopen.overlay.FoldOverlayService"))
                                    found = true;
                            }
                        }
                    } else query.destroyForcibly();
                    enabled.set(found);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    enabled.set(false);
                    System.err.println("Cannot read accessibility gate; exiting");
                    System.exit(1);
                    return;
                }
            }
        }, "duo-accessibility-gate").start();
        SensorEventListener listener = new SensorEventListener() {
            public void onSensorChanged(SensorEvent event) {
                long now = SystemClock.elapsedRealtime();
                policy.sample(event.values[0], now);
                boolean allowed = enabled.get() && power.isInteractive();
                if (!allowed) policy.lastMotion = -1;
                bridge.setConcurrent(policy.wantsConcurrent(now, allowed));
            }
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        if (!sensors.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_GAME, handler))
            throw new IllegalStateException("Cannot subscribe to hinge sensor");
        handler.post(new Runnable() {
            public void run() {
                boolean allowed = enabled.get() && power.isInteractive();
                if (!allowed) policy.lastMotion = -1;
                bridge.setConcurrent(policy.wantsConcurrent(SystemClock.elapsedRealtime(), allowed));
                handler.postDelayed(this, 100);
            }
        });
        System.out.println("ready: " + hinge.getName());
        Looper.loop();
        sensors.unregisterListener(listener);
        bridge.setConcurrent(false);
        lock.release();
        lockFile.close();
    }

    static void selfTest() {
        Policy p = new Policy();
        check(!p.wantsConcurrent(0, true), "unknown angle");
        p.sample(90, 0);
        check(!p.wantsConcurrent(0, true), "no motion on startup");
        p.sample(91, 10);
        check(p.wantsConcurrent(10, true), "motion activates");
        check(!p.wantsConcurrent(10, false), "screen off");
        check(p.wantsConcurrent(1509, true), "before timeout");
        check(!p.wantsConcurrent(1510, true), "stall timeout");
        p.sample(3, 1600);
        check(!p.wantsConcurrent(1600, true), "closed endpoint");
        p.sample(177, 1700);
        check(!p.wantsConcurrent(1700, true), "flat endpoint");
        p.sample(176, 1800);
        check(p.wantsConcurrent(1800, true), "fold from flat");
        p.sample(Float.NaN, 1900);
        check(!p.wantsConcurrent(1900, true), "invalid sensor");
        System.out.println("9 policy checks passed");
    }
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
