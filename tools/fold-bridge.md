# Fold7 concurrent-display bridge

This optional shell-privileged helper temporarily requests Samsung's state 4
(`CONCURRENT_INNER_DEFAULT`) while the hinge moves between 3 and 177 degrees.
It releases at either endpoint, after 1.5 seconds without meaningful motion,
within 100 ms of screen-off, and when its process dies (the request belongs to
the process's Binder connection). No settings override survives a reboot.

The helper runs entirely on the phone and exposes no network listener. It
checks once per second that the Fold7 app's accessibility service is enabled.
It takes no wake lock. Only SM-F966 devices running Android 16 are accepted;
state identifiers are vendor-specific. Other models require separate validation.

Build and run the deterministic policy checks:

```sh
bash tools/build-fold-bridge.sh
```

Install/start, replacing `PHONE` with an already-authorized ADB serial:

```sh
adb -s PHONE push build/fold-bridge/duo-fold-bridge.jar /data/local/tmp/duo-fold-bridge.jar
adb -s PHONE shell 'CLASSPATH=/data/local/tmp/duo-fold-bridge.jar nohup app_process /system/bin FoldDisplayBridge >/data/local/tmp/duo-fold-bridge.log 2>&1 </dev/null & echo $! >/data/local/tmp/duo-fold-bridge.pid'
adb -s PHONE shell cat /data/local/tmp/duo-fold-bridge.log
```

Stop the helper (and therefore release its own concurrent-display request):

```sh
adb -s PHONE shell 'p=$(cat /data/local/tmp/duo-fold-bridge.pid); case "$p" in ""|*[!0-9]*) exit 1;; esac; if tr "\000" " " </proc/$p/cmdline | grep -q FoldDisplayBridge; then kill "$p"; fi'
```

An advisory lock prevents duplicate bridge processes. A reboot stops the helper;
starting it again requires ADB. App install alone cannot grant Android's
privileged display-state permission. The `--probe` option explicitly requests
concurrent state for 1.5 seconds, then releases it, to verify firmware support.
Do not use the probe while another application owns a display-state override.

Verified on SM-F966U1 Android 16: the probe successfully requests state 4 and
returns to physical OPENED state 3. Killing the process with SIGKILL while state
4 was active also restored state 3. The detached helper subscribed to the live
hinge sensor and remained running with its accessibility gate. Actual moving-hinge visual continuity still
requires folding the device; policy tests cannot substitute for that check.
