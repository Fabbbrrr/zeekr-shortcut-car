# Slice 07 — playback / overlay / screen / setup / recovery / share

Auditor: pi (orcarouter-qwen3.8-flash-next, local). Date: 2026-11-15. Files read: 24 source files in `playback/`, `overlay/`, `screen/`, `setup/`, `recovery/`, `share/`; tests `FloatingActionTest`, `PlaybackViewportTest`, `FileShareServerTest`, `LocalNetworkTest`, `DisplayWatchTest`, `ScreenTransitionsTest`, `VehicleScreenTest`, `SetupShotsTest`; plus `AndroidManifest.xml`, `app/build.gradle.kts`, `storage/FootageLocks.java`, `service/RecordingFloatingService.java`, `zeekr/DiagnosticsActivity.java`, `profile/RecordSpecs.java`, `camera/CameraSlots.java`, `telemetry/InfoBar.java` (cross-checks only).

One section per finding, most serious first. Skip anything cosmetic unless it is cheap and visible.

## F-07.01 — PlaybackAudioFocus never unregisters after a permanent focus loss

- **Grade:** confirmed (both paths read in the same file)
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/playback/PlaybackAudioFocus.java:75` (guard) and `:86` (`AUDIOFOCUS_LOSS`)
- **What happens:** `AUDIOFOCUS_LOSS` sets `held = false`. `abandon()` then returns at its `if (!held …) return;` guard, so `abandonAudioFocusRequest()` never runs. The system keeps the `AudioFocusRequest` and its `OnAudioFocusChangeListener` registered for the life of the process, and the listener chain (`PlaybackAudioFocus` → `Listener` → `TimelinePlayerActivity`) keeps the destroyed activity, its TextureViews and its decoder reachable.
- **Evidence:** `PlaybackAudioFocus.java:74-81` (abandon guarded by `held`), `:85-89` (loss clears `held`), `:55` (the listener + main-looper Handler the request holds). `TimelinePlayerActivity.java:2001` calls `abandon()` in `onDestroy()` — it is a no-op on this path. The class doc says long losses pause and re-request on the next play; it never says the registration survives the screen closing.
- **Fix:** in `PlaybackAudioFocus.abandon()`, call `abandonAudioFocusRequest(request)` whenever a request was ever granted, not only while `held` is still true — track "granted once" separately from "still held".
- **Cost / risk:** the listener must still fire for a loss-then-regain inside the screen, so the new flag must only gate the unregister, not the pause/resume callbacks. No unit test touches this class; nothing in the suite covers focus.
- **Test:** no unit test possible — needs device (or a Robolectric test with a shadowed AudioManager).

## F-07.02 — "Select all" selects photo groups inside collapsed date sections

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/playback/ExpandablePhotoGroupAdapter.java:149-153` against `:90-95`
- **What happens:** `buildFlattenedList()` shows a section's groups only `if (section.isExpanded())`, and `DateSection` defaults `expanded` to today only. `selectAll()` iterates every section and adds every group, expanded or not. The user sees a handful of ticked rows, the counter says more, and delete removes photos from days that were never on screen.
- **Evidence:** `ExpandablePhotoGroupAdapter.java:92` (expansion gate) versus `:152` (no such gate); `DateSection.java` constructor (`expanded = isToday(date)`); `PhotoPlaybackActivity.java:891` `deleteSelected()` acts on `adapter.getSelectedGroups()` and on `group.files()`.
- **Fix:** in `ExpandablePhotoGroupAdapter.selectAll()`, add items only from expanded sections — or show the count of hidden picks in the confirm dialog the delete path already builds.
- **Cost / risk:** changes what "全选" means when history exists; users may expect it to mean everything. No test covers select-all; `PlaybackViewportTest` covers geometry only.
- **Test:** unit test on the adapter: two sections, one collapsed, `selectAll()` then `getSelectedGroups()`; needs a Robolectric context for the current adapter.

## F-07.03 — PhoneShare can leave its HTTP server running when the host activity dies

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/share/PhoneShare.java:159` and `:197`
- **What happens:** the only stop path is the dialog's `setOnDismissListener`. `PhoneShare` has no lifecycle hook and no owner keeps the server. If the hosting activity is finished while the dialog is up (playback screen closes, activity destroyed by the system), nothing calls `stop()` and NanoHTTPD keeps its listening socket and its worker thread until the process dies.
- **Evidence:** `PhoneShare.java:159` (dismiss → `stop()`), `:197-203` (`stop()`), no other caller of `server.stop()` in the file; `PhoneShare.show()` is called from three activities (`PhotoPlaybackActivity.java:417`, `zeekr/TimelinePlayerActivity.java:1408`, `zeekr/DiagnosticsActivity.java:280`) and none of them keeps a handle. What is unproven: whether the framework dismisses the dialog (and so fires the listener) when the activity is destroyed.
- **Fix:** have `PhoneShare.show()` return the `FileShareServer` (or take a lifecycle owner) and stop it from the caller's `onDestroy()`; keep the dismiss path as well.
- **Cost / risk:** three call sites to touch. Double-`stop()` must stay harmless — NanoHTTPD tolerates it, and `stop()` is already wrapped in try/catch.
- **Test:** no unit test possible — needs device; a Robolectric test can show the activity, fire `show()`, destroy the activity, and assert the port is closed.

## F-07.04 — PhoneShare binds a socket, enumerates network interfaces and builds the QR bitmap on the main thread

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/share/PhoneShare.java:72`, `:85`, `:185`
- **What happens:** `show()` runs on the main thread at all three call sites, and it does a netlink query for every interface (`LocalNetwork.enumerate()`), a synchronous `server.start()` (bind + listen on port 0), and a 420×420 `int[176400]` QR encode plus a fresh `Bitmap`. Every one of those is off-thread work on the UI thread, inside the tap that opens the dialog.
- **Evidence:** call sites — `PhoneShare.java:72` (`enumerate()`), `:85` (`start()`), `:185` (`QrCode.encode`); all three callers invoke from a click handler or the main looper (`PhotoPlaybackActivity.java:417`, `TimelinePlayerActivity.java:1408`, `DiagnosticsActivity.java:280` via `mainHandler.post`).
- **Fix:** do the interface scan before `show()` and post the dialog build (or at least the `QrCode.encode`) to the main handler after a background scan; keep `start()` on the calling thread only if the whole method moves off-thread.
- **Cost / risk:** the dialog must still be built on the main thread. `LocalNetworkTest` covers the sort, not the timing, so no test breaks.
- **Test:** no unit test possible — needs device; measure the tap-to-dialog delay with `Choreographer` or StrictMode disk/network penalty.

## F-07.05 — Photo playback scans the USB directory and stats every file on the main thread

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/playback/PhotoPlaybackActivity.java:752` (scan) and `:640` / `ExpandablePhotoGroupAdapter.java:295`, `:305` (per-file stat per bind)
- **What happens:** `updatePhotoList()` runs `listFiles()` on the photo directory, then groups and rebuilds the adapter; every bind and every lane load calls `exists()` and `lastModified()` again. All of it is on the main thread, on entry, on refresh, and after every delete and lock change. On the head unit the photo directory is exFAT on a stick, which the storage notes already report as slow and occasionally `EIO`.
- **Evidence:** `PhotoPlaybackActivity.java:752`, `:634`, `:640`; `ExpandablePhotoGroupAdapter.java:294-306` (`exists()` + `lastModified()` per thumbnail per bind); the delete path already shows the storage team's judgement — `storage/FootageLocks.java:244-252` does its file work on its own executor and posts the result back to the main looper, while this screen does not.
- **Fix:** scan and stat on a background thread (reuse `FootageLocks`-style executor or the storage state already maintained in `storage/StorageState`), deliver the grouped list to the adapter on the main thread.
- **Cost / risk:** the delete and lock callbacks assume the lists they touch are stable; only the scan should move, and `buildFlattenedList()` must still run on the main thread.
- **Test:** no unit test possible for timing — needs device; a Robolectric test can at least assert the scan result shape.

## F-07.06 — Restore reports "overlays" even when the background service start throws

- **Grade:** unknown
- **Severity:** P2 edge case or rough edge
- **What happens:** `OverlayCoordinator.sendToRecordingFloating()` calls `context.startService()` with no guard, and `SideViewService.start()` the same. `Recovery.restoreOnLaunch()` reaches both from the background (keep-alive worker, wake, screen-on), where a plain background service start can be refused on modern Android — this codebase already knows: `overlay/QuickActions.java:119-122` wraps the same call in try/catch for exactly that reason. The first throw aborts the rest of `restoreOnLaunch()`, `Recovery.restore` swallows it as a warning, and the blackbox line claims the overlays were restored.
- **What would prove it:** a blackbox or logcat line `restore failed (keep-alive-task)` / `restore failed (wake)` in a parked-then-woken capture, against the `恢复（…）：overlays` line it skips.
- **Evidence:** `overlay/OverlayCoordinator.java:169-173` (`startService`, no guard) and `:78` (`SideViewService.start` from `restoreOnLaunch`); `recovery/Recovery.java:65-67` (catch → `AppLog.w`, so the run stops there); `recovery/KeepAliveWorker.java:44` calls `Recovery.restore(...)` from its worker thread; `QuickActions.java:119-122` guards the same call.
- **Fix:** guard both starts the way `QuickActions` does (log, continue), or use `startForegroundService()` for the two services `Recovery` restores, so one refusal cannot cancel the others.
- **Cost / risk:** unknown whether this head unit grants an exemption (accessibility service, existing foreground service, `SYSTEM_ALERT_WINDOW`), so the fault may never have fired. Do not change behaviour before one capture shows the refusal.
- **Test:** no unit test possible — needs device.

## F-07.07 — The portrait two-column grid in photo playback can never run

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/playback/PhotoPlaybackActivity.java:246-256` against `app/src/main/AndroidManifest.xml:152`
- **What happens:** `initViews()` picks `GridLayoutManager(2)` with a span lookup for portrait and `LinearLayoutManager` otherwise. The manifest pins this activity to `android:screenOrientation="landscape"`, so only the linear branch ever runs and the span-size lookup is dead. The comment above it ("竖屏2列，横屏1列") describes a path the app never takes.
- **Evidence:** `AndroidManifest.xml:152` (`screenOrientation="landscape"` on `.playback.PhotoPlaybackActivity`); `PhotoPlaybackActivity.java:247` is the only reader of the orientation there, and `getSpanSize` has no other caller.
- **Fix:** drop the portrait branch and the span lookup, or drop the manifest pin — pick one and make the comment match. Same shape as `TimelinePlayerActivity` (`AndroidManifest.xml:159`), which has no portrait branch at all.
- **Cost / risk:** none functionally; it is a lie in the code plus dead layout code. `HardcodedTextTest` / `LayoutHygieneTest` do not cover it.
- **Test:** no unit test possible — needs device.

## F-07.08 — Recovery clears the persisted "left for screen-off" flag before the launch it guards

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/recovery/Recovery.java:87` before `:100`
- **What happens:** `bringBackUiAfterScreenOn()` clears `didUiLeftForScreenOff` first, then launches the activity. If `startActivity()` throws (it is caught a few lines below and only logged), the flag is already gone, so no later screen-on, wake, or keep-alive pass will bring the UI back for the rest of the session — the user sees the head-unit desktop.
- **Evidence:** `Recovery.java:87` (clear) versus `:100` (`startActivity` inside the `try` whose `catch` at `:104` only logs `AppLog.w`); the early return at `:88` (`StallWatch.isForeground()`) also clears the flag for a UI that is still in front — that one is intended per the comment.
- **Fix:** clear the flag only after `context.startActivity(intent)` returns; log the failure and leave the flag set so the next pass retries.
- **Cost / risk:** the dedupe timestamp `broughtUpAtMs` must not advance on a failed launch either, or the retry window is spent. Nothing in the suite covers `Recovery`.
- **Test:** no unit test possible — needs device.
