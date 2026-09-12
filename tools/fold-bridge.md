# Fold7 concurrent-display bridge

This optional shell-privileged helper is controlled by **Tune → Keep both displays awake · test** in Duo Open Fold 7. The toggle is **off by default** and is separate from the normal accessibility effect.

When opted in and the phone is awake, it requests both screens even at rest. It selects Samsung state 4 (`CONCURRENT_INNER_DEFAULT`) if enabled while open, or state 5 (`CONCURRENT_OUTER_DEFAULT`) if enabled while closed. **That primary screen stays fixed until the toggle is turned off.** The secondary screen shows a frozen snapshot and is not an independent interactive app display. This is an experimental visual test, not a replacement for normal phone operation.

The mode deliberately uses more battery to prelight the second panel. It no longer switches states 4 and 5 during folding: physical tests caught both panels OFF during those switches. A cancellation callback clears the helper's request state and permits a retry after 250 ms. Samsung may still cancel requests during lock/sleep transitions, so this does not guarantee flicker-free operation.

The helper checks the app's read-only, DUMP-permission-protected provider once per second. The provider requires both the test toggle and accessibility service to be enabled. Turning either off releases the request on the next poll; screen-off releases it within about 100 ms. Process death also releases it through Binder. No wake lock or network listener is used. Only SM-F966 devices running Android 16 are accepted. Other models require separate validation.

The firmware's standard hinge sensor reported only 0°, 90°, and 180° during physical tests. The Samsung folding-angle sensor refused a shell subscription. Prelighting avoids waiting for those coarse events to wake a panel; it does not provide continuous hinge-angle tracking.

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

Earlier device verification on SM-F966U1 Android 16 confirmed state 4 lights both panels and process death restores the physical state. Current deterministic tests cover default-off gating, fixed-mode prelighting across endpoints, sleep/wake, opt-out reset, and invalid samples. Physical fold continuity still needs operator verification; requested state and panel power alone do not prove a visible animation.

Live verification of the fixed-mode build observed state 5 and both physical panels ON, but also transient returns to physical state 0 and an OFF panel while folding. The helper was not changing modes at those points; Samsung canceled or reconfigured the request. Treat this as a test mode with known flicker, not a seamless dual-display implementation. Default-off provider gating was verified on installation.
