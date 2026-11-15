# Slice 06 — root (com.kooo.evcam), button/, update/, blackbox/, storage/, recording/, service/, manifest, CI

Auditor: pi / orcarouter-qwen3.8-flash-next-uncensored-iq4_xs. Date: 2026-11-15. Files read: 41 — cited: `MainActivity.java`, `AppLog.java`, `StorageCleanupManager.java`, `BootReceiver.java`, `service/RecordingFloatingService.java`, `button/MediaMove.java`, `button/StaleGatt.java`, `app/src/main/AndroidManifest.xml`, `.github/workflows/build.yml`, `.github/scripts/release_notes.py`; also read in full or in part: `ZeekrShortcutApp.java`, `KeepAliveReceiver.java`, `KeepAliveWorker.java`, `KeepAliveManager.java`, `KeepAliveProvider.java`, `UserExit.java`, `WakeUpHelper.java`, `CameraForegroundService.java`, `ImageAdjustFloatingWindow.java`, `service/CameraRecordingService.java`, `button/ScanReceiver.java`, `button/ScanProbe.java`, `button/SmartButtons.java`, `button/ButtonLink.java`, `button/CarMoves.java`, `button/DeviceLabel.java`, `button/SentrySwitch.java`, `blackbox/BlackBox.java`, `blackbox/ChangeTracker.java`, `blackbox/VolumeEvents.java`, `storage/AutoLock.java`, `storage/FootageLocks.java`, `storage/LockWindow.java`, `storage/StorageState.java`, `recording/ScreenOffPlan.java`, `recording/ScreenOffRecording.java`, `update/DownloadIntegrity.java`, `update/GithubReleases.java`, `update/ReleaseNotes.java`, `update/UpdateFlow.java`, `update/UpdateInstallGuide.java`, `update/VersionName.java`, `app/build.gradle.kts`, `gradle.properties`, tests `VersionNameTest`, `DownloadIntegrityTest`, `DeviceLabelTest`, `ScreenOffPlanTest`.

No gradle run, no edits outside this file. Update path (`DownloadIntegrity` + `GithubReleases`) is sound: https-only including every redirect hop, digest and size both checked, partial file deleted on failure, unparseable versions never offered.

## F-06.01 — image-adjust overlay outlives its activity; nothing dismisses it

- **Grade:** confirmed
- **Severity:** P1 wrong behaviour in normal use
- **Where:** `app/src/main/java/com/kooo/evcam/MainActivity.java:2928`
- **What happens:** `showImageAdjustFloatingWindow()` adds a `TYPE_APPLICATION_OVERLAY` window built on the activity context and stores the object in an instance field. `onDestroy` (line 2695) never touches `imageAdjustFloatingWindow` — the field is read only inside that one method (lines 2923, 2924, 2929, 2932, 2935). When the activity is destroyed while the panel is open, the window stays on screen with no owner, the dead activity stays reachable through it, and the new activity instance starts with a null field, so opening the panel again stacks a second window; only the close button on the old one can remove it.
- **Evidence:** the only `dismiss()` calls are inside `showImageAdjustFloatingWindow()` itself; `ImageAdjustFloatingWindow.dismiss()` (line 143) is the only path that calls `windowManager.removeView`. `dismissListener` is set but never invoked on destroy.
- **Fix:** in `MainActivity.onDestroy`, call `imageAdjustFloatingWindow.dismiss()` (or add a `detach()` that removes the view without firing the listener) before clearing listeners.
- **Cost / risk:** dismiss also calls `adjustManager.saveParamsToConfig()` — decide whether a destroy should persist pending slider moves. No unit test covers this window.
- **Test:** no unit test possible — needs device; Robolectric could assert `isShowing() == false` after `finish()`.

## F-06.02 — hourly storage cleanup dies with the main screen

- **Grade:** confirmed
- **Severity:** P1 wrong behaviour in normal use
- **Where:** `app/src/main/java/com/kooo/evcam/MainActivity.java:345`
- **What happens:** `StorageCleanupManager.start()` is called from `MainActivity.onCreate` and `stop()` from `MainActivity.onDestroy` (line 2746), so the `ScheduledExecutorService` that owns the 30-second first pass and the hourly pass only exists while the main screen exists. The recording pipeline, the blackbox and `StorageState` all deliberately live on the process, not the screen; cleanup does not. After the main screen is destroyed and recording keeps running, the photo quota is never enforced again and the drive fills.
- **Evidence:** `StorageCleanupManager` has no other caller in the app (grep over `app/src/main/java`); its own comment promises "once every hour".
- **Fix:** drive the schedule from the process — start it from `ZeekrShortcutApp.onCreate` (or from `StorageState`'s existing background executor) and drop the activity stop, keeping the existing `FootageLocks.guard()` around the delete pass.
- **Cost / risk:** cleanup then runs in every process life, including processes woken by the scan PendingIntent or WorkManager; the photo-delete path and `StorageGuard.enforce` must be safe without an activity (they already are — both take the guard).
- **Test:** `FootageLocksTest` covers the lock rules; the scheduler itself needs a device or Robolectric clock test.

## F-06.03 — crash handler and warnings.log only arm once the main screen has run

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/MainActivity.java:295`
- **What happens:** `AppLog.init()` is called once, from `MainActivity.onCreate`. It is the only thing that installs the `UncaughtExceptionHandler` (`AppLog.java:70`) and the only thing that rotates the session logs. `persistWarning()` returns immediately while `sAppContext` is null (`AppLog.java:317`), so in a process that never creates `MainActivity` — woken by the scan `PendingIntent`, by WorkManager, by the provider path with autostart off, or by a Bluetooth broadcast — warnings and errors never reach `warnings.log`, and a crash there saves nothing.
- **Evidence:** single `AppLog.init` call site; `AppLog.persistWarning` early return; `ScanProbe`'s comment already records that the app can only read its own process out of logcat, which is the only remaining witness in those processes.
- **Fix:** call `AppLog.init(this)` from `ZeekrShortcutApp.onCreate` (it already attaches the blackbox there) and keep the MainActivity call as a no-op second pass.
- **Cost / risk:** `rotateSessionLogs` renaming the previous log at process start is then normal, not only on screen start; the diagnostics report reads the same files.
- **Test:** no unit test possible — needs device; assert `warnings.log` gains a line from a process that never opened the main screen.

## F-06.04 — floating service leaves its bind behind

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java:290`
- **What happens:** `onCreate` binds `CameraRecordingService` with `BIND_AUTO_CREATE` from a fresh thread (line 178), and `onDestroy` unbinds only `if (isServiceBound)`. A bind that has not called back yet is not "bound", so a floating service that is created and destroyed quickly (overlay permission missing on the next show, Recovery pulling it back, sticky restart) leaves the `ServiceConnection` registered for a dead service and keeps `CameraRecordingService` alive by bind forever. Repeating the cycle registers the same connection again.
- **Evidence:** `isServiceBound` is set only in `onServiceConnected` (line 148); no `unbindService` outside the guarded branch; `CameraRecordingService` does nothing but log — its start/stop entries were deleted on 2026-11-03, so a surviving bind keeps a no-op service resident.
- **Fix:** remember the bind was requested, unbind unconditionally in `onDestroy` (catch `IllegalArgumentException`), and bind on the main looper instead of a private thread.
- **Cost / risk:** unbinding a connection that never connected is legal but throws in some releases; the catch is part of the fix.
- **Test:** no unit test possible — needs device.

## F-06.05 — background voice action reports success for a start Android drops

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/button/MediaMove.java:63`
- **What happens:** `voice()` calls `startActivity` from the application context with `FLAG_ACTIVITY_NEW_TASK` and returns `true` unless it throws `ActivityNotFoundException`. A background activity start is blocked silently by the platform (no exception) unless the app has a visible window or the overlay permission — which the manifest declares exactly for that reason ("bypass the background activity start limit"). So a灵动贴 action mapped to "voice assistant" reports success and does nothing when the main screen is closed and overlay permission was never granted.
- **Evidence:** `MediaMove.perform` returns that `true` to `SmartButtons.act`, which only toasts on `false`; the app treats the overlay permission as a precondition elsewhere (`WakeUpHelper.hasOverlayPermission` gates every overlay service, `SettingsPreferenceFragment.java:105`).
- **Fix:** in `voice()`, check `Settings.canDrawOverlays(app)` before starting, or report failure when neither the permission nor a visible window is present, so `SmartButtons.act` shows the "not available" toast.
- **Cost / risk:** changes behaviour only in the blocked case; no test covers `MediaMove`.
- **Test:** no unit test possible — needs device with the overlay permission revoked.

## F-06.06 — a cancelled drag swallows the next tap on the floating button

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java:505`
- **What happens:** the touch listener handles `ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP` and no `ACTION_CANCEL`. A drag while the position is locked sets `slidWhileLocked` (line 478) and the next `ACTION_UP` consumes it without performing the action. If that gesture ends in `ACTION_CANCEL` — finger lifted off the button, gesture stolen, window removed mid-drag — the flag stays set, and the following legitimate tap is eaten: neither tap nor long-press runs.
- **Evidence:** `slidWhileLocked` is cleared only inside the `ACTION_UP` branch (line 506); `ACTION_DOWN` resets `isDragging` and `longPressFired` but not this flag.
- **Fix:** reset `slidWhileLocked = false` in `ACTION_DOWN` alongside the other per-gesture flags, and handle `ACTION_CANCEL` (remove the long-press callback, clear the flags).
- **Cost / risk:** none known; no test covers the listener.
- **Test:** no unit test possible — needs device.

## F-06.07 — the dead-GATT repair may never run; reflection is the whole feature

- **Grade:** unknown
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/button/StaleGatt.java:63`
- **What happens:** the class reads `BluetoothAdapter.mManagerService` reflectively and then `IBluetoothManager.getBluetoothGatt`, both non-SDK. For an app on `targetSdk = 36` that lookup can be denied, in which case every call returns "can't look", the swap never happens, and the scan stays broken after a Bluetooth restart — the documented fallback ("wait for an app restart"). The unit tests pass because they build their own objects and never touch the hidden members, so CI green does not cover the only path that matters on the car.
- **Evidence:** `StaleGattTest` (all nine cases) runs against fakes; the same reflection appears in `ScanProbe.java:117` with the same failure mode. What would settle it: one blackbox line saying `can't look (…NoSuchFieldException)` instead of `dead; replaced N service(s)`.
- **Fix:** if a blackbox line confirms denial, get the live manager through `BluetoothManager`'s hidden binder field only when accessible and record it; otherwise leave the fallback and say so in the class comment.
- **Cost / risk:** none — the code already degrades; only the claim needs proof.
- **Test:** no unit test possible — needs device; assert a "replaced" line from a blackbox export after a Bluetooth restart.

## F-06.08 — manifest declares permissions nothing ever asks for

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/AndroidManifest.xml:17`
- **What happens:** `READ_MEDIA_VIDEO` (line 17) and `READ_MEDIA_IMAGES` (line 18) are declared and referenced nowhere in `app/src` — not requested, not checked; `MainActivity.getRequiredPermissions()` asks for `CAMERA` only on Android 13+. `READ_LOGS` (line 7) is a signature/privileged/development permission, so declaring it grants nothing to a normal app; `ScanProbe.bluetoothLog()` only ever reads this app's own process, which its own comment admits.
- **Evidence:** grep for `permission.READ_MEDIA_*` and `permission.READ_LOGS` across `app/src` returns the manifest lines only; the storage path that actually needs grant is `MANAGE_EXTERNAL_STORAGE`, which the settings permission page does request (`SettingsPreferenceFragment.java:102`).
- **Fix:** drop the two media lines, or request them where the media permission state is shown; keep `READ_LOGS` only if the diagnostic section is renamed to "our own process".
- **Cost / risk:** removing a declaration changes nothing at runtime here; `HardcodedTextTest`/parity tests do not read the manifest.
- **Test:** no unit test possible — needs device.

## F-06.09 — the release-notes step cannot fail, and every release is a draft

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `.github/workflows/build.yml:89`
- **What happens:** the step runs under `set -euo pipefail`, but `release_notes.py` returns `None` for a version with no `## [x.y.z]` section in `CHANGELOG.md` (`.github/scripts/release_notes.py:47`) and exits 0 — so a tag whose changelog section is missing publishes with no notes and a green check. The same step pair cannot show what its comment says: `draft: true` (line 102) applies to every release, and GitHub never labels a draft as Latest, so the comment at line 98 ("no suffix means stable, shows as Latest") can never be true, and `prerelease:` only decides the label of a release nobody but the owner can see.
- **Evidence:** `changelog_section()` treats "not found" as normal; the unit-test step (line 43) is the only real gate in the job and it does gate.
- **Fix:** make `release_notes.py` exit non-zero when `stable` and the section is missing (or when the version argument is empty), and either drop the Latest claim from the comment or make stable tags non-draft.
- **Cost / risk:** a stricter script fails release builds that today pass silently; only tag pushes are affected.
- **Test:** run `python3 .github/scripts/release_notes.py 0.0.0-ghost; echo $?` and expect non-zero.

## F-06.10 — boot receiver schedules work it may not live to run

- **Grade:** single-source
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/BootReceiver.java:57`
- **What happens:** `onReceive` returns immediately after posting a five-second delayed handler on the main looper; the receiver's process is free to be killed the moment `onReceive` returns, so `performDelayedInit` — the `TIME_TICK` registration and the WorkManager enqueue — can be skipped. The class comment already records that this container never delivered the boot broadcast in three reboots, so on the car this path is dead weight and the real recovery is `UserExit.rebootedSinceExit` plus the surviving WorkManager entry.
- **Evidence:** no `goAsync()`/`ResultReceiver` in `BootReceiver`; the comment at the top of the file and platform notes §3.6 state the broadcast never arrives; `UserExit` therefore judges a real reboot from boot-time arithmetic instead (`UserExit.java` `rebootedSinceExit`).
- **Fix:** do the work inside `goAsync()`'s `PendingResult` (or move both steps into `KeepAliveWorker`), and note in the comment that on this head unit the boot path is a fallback that has never fired.
- **Cost / risk:** none known; nothing tests `BootReceiver`.
- **Test:** no unit test possible — needs device.
