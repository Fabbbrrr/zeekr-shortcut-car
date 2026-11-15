# Slice 02 — zeekr

Auditor: pi / claude. Date: 2026-11-15. Files read: 34 of the 47 under `app/src/main/java/com/kooo/evcam/zeekr/` — the ones cited are named below, plus the call sites in `camera/SingleCamera.java`, `settings/SettingsPreferenceFragment.java`, `screen/ScreenState.java`, `button/ScanProbe.java`, `camera/StallWatch.java`, and `docs/polish/{README,BASELINE}.md`.

Nothing in this slice reached P0 or P1. The services, windows, mesh and trigger code pair their resources and say why in comments; the findings below are what survived that reading.

## F-02.01 — The diagnostics report spawns `logcat` and never reaps it

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/zeekr/DiagnosticsCollector.java:1453`
- **What happens:** `appendLogcat` runs `logcat -d -v time -t N`, reads stdout to EOF and closes the reader (`:1466`). The `Process` is never `destroy()`ed and never `waitFor()`d, so its stdin and stderr pipes and the process handle stay open until the finalizer runs. Every report — and every press of Refresh, which calls `runCollection` (`DiagnosticsActivity.java:108`) — leaves another one.
- **Evidence:** The tree's two other subprocess spawns do reap: `app/src/main/java/com/kooo/evcam/button/ScanProbe.java:168` and `app/src/main/java/com/kooo/evcam/camera/StallWatch.java:758` both call `process.destroy()`. This call site does not. No test covers `appendLogcat`.
- **Fix:** Close stdout, then `p.destroy()` (and `p.waitFor()`) in a `finally` in `DiagnosticsCollector.appendLogcat`.
- **Cost / risk:** None to the report content. `DiagnosticsCollectorTest` covers the pure text builders only, so it will not notice.
- **Test:** no unit test possible — needs device (count `/proc/self/fd` before and after ten reports).

## F-02.02 — The diagnostics screen writes to its views after the activity is gone

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/zeekr/DiagnosticsActivity.java:118`
- **What happens:** Collection runs on a plain thread (`:108`) and posts its result to `mainHandler` with no `isDestroyed()` check, so the lambda runs `reportView.setText`, `setButtonsEnabled` and `gaps.stopAfter` on views of an activity the user already left. The save path has the same shape (`:264` → `:266`, which runs the `then` consumer for "send to phone").
- **Evidence:** The same file guards the Choreographer watch with `!isDestroyed()` (`:165`), and `TimelinePlayerActivity.showScan` guards the identical pattern (`:634`). The posts here are the only unguarded ones in the package. Nothing crashes — the views survive the finish — but a late `setText` and a late `PhoneShare.show` can land on a dead screen.
- **Fix:** Return early from both posted lambdas when `isDestroyed()`.
- **Cost / risk:** The `sendToPhone` consumer must still run when the activity is alive; guard the lambda, not the caller.
- **Test:** no unit test possible — needs device.

## F-02.03 — The GPU dewarp route can hold the camera round thread for up to 2.5 s

- **Grade:** single-source
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/zeekr/PreviewDewarp.java:121` (and `:111`)
- **What happens:** `FisheyeGlPipe.start()` blocks its caller until the pipeline thread reports ready, up to `START_TIMEOUT_MS` = 1500 ms (`FisheyeGlPipe.java:239`), and `release()` blocks up to `RELEASE_TIMEOUT_MS` = 1000 ms (`:352`). Both run inside `PreviewDewarp.LOCK` (`:43`) on the camera round thread, because `SingleCamera.surfaceFor` is the caller (`camera/SingleCamera.java:2636`, javadoc: 相机线程). A session rebuild that swaps the pipe therefore delays that round's callbacks by up to 2.5 s in the worst case.
- **Evidence:** Read from the code only — the timeouts are deliberate (both javadocs say the caller waits on purpose), and nothing measured shows them firing. The log line that would prove it is `FisheyeGlPipe.java:240` ("1500ms 内没准备好，照旧直连"); no captured logcat in the tree contains it.
- **Fix:** None needed unless the timeout is seen in a field log. If it is: do the wait off the round thread, or cut `START_TIMEOUT_MS`.
- **Cost / risk:** Moving the wait means the caller must accept a pipe that reports ready later; `PreviewDewarp.isActive` already treats a dead pipe as absent, so the mesh path covers it.
- **Test:** no unit test possible — needs device.

## Swept, nothing found

Checked across the 47 files and found paired or documented in every case: `VelocityTracker` obtain/recycle (`RearViewMirrorView.java:807` → `:991`, recycle in `finally`); `addView`/`removeView` and the editor singletons (dismissed from `SettingsPreferenceFragment.java:262` `onStop`); Presentation show/dismiss (`SideViewHudWindow.java:297`); `onSurfaceTextureDestroyed` → `CanvasSlot.keep` hand-off and the orphan case (`RearViewMirrorService.java:900`); `MediaExtractor`/`MediaMetadataRetriever` release in `finally` (`RecentRecordings.java:177`, `ClipDurations.java:73`); per-frame allocation in the mesh and draw paths (`FisheyeMesh.prepare` reuses `grid`/`point`); boxed comparisons (`SideViewService.evaluate` uses `Integer.valueOf(...).equals(...)`); static mutable state (`PreviewDewarp` volatile fields + `LOCK`, `ClipDurations` `ConcurrentHashMap` with size+mtime in the key); `SharedPreferences` listeners held by the registrant (`FourLaneContainer.java:515`, `PreviewDewarp.java:136`); bounded retries with cancel (`SideViewService.java:1078`, `RearViewMirrorService.java:77`); scan-round cancellation guarded by `isDestroyed()` (`TimelinePlayerActivity.java:634`); no `BroadcastReceiver` and no `commit()` in the package.
