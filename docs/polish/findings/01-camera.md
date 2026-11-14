# Slice 01 — com.kooo.evcam.camera

Auditor: pi (orcarouter-qwen3.8-flash-next-uncensored-iq4_xs, medium). Date: 2026-11-15. Files read: 7 cited — SingleCamera.java, CodecVideoRecorder.java, FootageAudio.java, plus CameraKeeper.java, CameraManagerHolder.java, MultiCameraManager.java, StorageGuard.java, StepLog.java, StallWatch.java, StallRules.java, FrameThrottle.java, FrameRateMeter.java, FpsRangePicker.java, EncodeSize.java, TargetBitrate.java, RecordRetries.java, Heartbeat.java, CameraSlots.java, CameraNeeds.java, CameraRounds.java, SharedSegmentName.java, StorageBudget.java, FootageAudioMath.java, PreviewFrameRates.java, ImageAdjustManager.java, VideoRecorder.java, CameraLiveness.java (code skimmed against their unit tests).

One section per finding, most serious first. Skip anything cosmetic unless it is cheap and visible.

## F-01.01 — Closing the JPEG channel drops an in-flight capture without failing it

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/camera/SingleCamera.java:3378`
- **What happens:** `closeJpegReader()` nulls `pendingJpeg` and returns. The pending callback is the anonymous `JpegCallback` from `takePicture`, whose `onFailed` is what triggers the preview-capture fallback; nulling it means nobody ever calls it. The capture request is already on the session, so the JPEG either never arrives or arrives to `onJpegAvailable` with `callback == null` and is discarded (line 3313-3314).
- **Evidence:** `closeJpegReader()` is called from the session-config-failure path (`SingleCamera.java:2845`, "drop the JPEG channel and retry") while a capture from `requestJpeg` (`:3340`) may still be outstanding. `MultiCameraManager.pressShutter` only resolves that lane through `onCameraPhotoDone`, which it reaches from the callback — so the lane is counted missing and the whole photo waits the full `PHOTO_SAVE_TIMEOUT_MS` (5 s, `MultiCameraManager.java:5393`) before finishing.
- **Fix:** in `SingleCamera.closeJpegReader()`, take the callback into a local, null the field, and call `onFailed("jpeg channel closed")` on it before returning.
- **Cost / risk:** the callback now fires twice for a lane if the image also arrives later — `onJpegAvailable` guards with `callback == null`, and `MultiCameraManager.onCameraPhotoDone` dedupes with `job.reported` (`:5596`). No unit test covers this path (Android handlers required).
- **Test:** no unit test possible — needs device.

## F-01.02 — Recovery retry never caps and never backs off, unlike its own javadoc

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java:2247`
- **What happens:** the javadoc on `switchToNextSegment` promises "失败时：使用5秒快速重试，最多重试6次（30秒内），之后回到正常1分钟间隔". The code has no cap and no ladder: `attemptRecovery` failure increments `recoveryAttempts` (`:2385`) and calls `scheduleRecoveryRetry()`, which always posts at the flat `RECOVERY_RETRY_INTERVAL_MS` (5 000 ms, `:254`) forever. `recoveryAttempts` only feeds log lines — its own declaration says so: `// 当前重试次数（只进日志）` (`:255`).
- **Evidence:** `scheduleRecoveryRetry` (`:2318-2334`) contains no comparison against `recoveryAttempts`. The only stop is the write-stall referee, which reports once and then stops watching (`scheduleWriteStallCheck`, `:4118-4145`), so once it has fired the 5 s retry loop can keep rebuilding a MediaCodec forever.
- **Fix:** in `CodecVideoRecorder.scheduleRecoveryRetry()`, cap the fast retries (6) and fall back to the segment timer afterwards, or drop the stale line from the javadoc on `switchToNextSegment` and state the real rule (flat 5 s until the referee stops the run).
- **Cost / risk:** adding a real cap changes behaviour on a genuinely broken encoder — the run would then sit idle instead of retrying, which is what the comment already claims. No unit test exists for the retry ladder.
- **Test:** unit test on `scheduleRecoveryRetry` with a fake `segmentHandler` recording delays; assert the delay grows after the 6th attempt.

## F-01.03 — `requestJpeg` reads the JPEG reader from the calling thread; the field is not volatile

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/camera/SingleCamera.java:3341`
- **What happens:** `jpegReader` is a plain field (`:154`) written on the camera/setup thread (`:3305`) and read here on the caller's thread. `takePicture` calls `requestJpeg` directly on the caller (main, from `MultiCameraManager.pressShutter`), and only the capture work afterwards hops to `backgroundHandler`. A stale null makes the lane take the preview-capture fallback even though the JPEG channel exists — the fallback is capped at preview resolution, so the photo comes out smaller than configured.
- **Evidence:** the field right beside it is deliberately `private volatile Handler backgroundHandler;` (`:134`) and is read the same way at `:3344`; `jpegReader`, `jpegSize` (`:160`) and `jpegDropped` are not, and the write at `:3305` happens on a different looper than this read.
- **Fix:** make `jpegReader` (and `jpegSize`/`jpegDropped`) volatile in `SingleCamera`, or move the whole `requestJpeg` decision inside the `handler.post(...)` block it already has.
- **Cost / risk:** low — volatile only orders the reference. Behaviour change is that a lane whose reader was just created now uses the JPEG path rather than the preview fallback. No test covers it.
- **Test:** no unit test possible — needs device.

## F-01.04 — Audio thread opens the microphone even after `stop()`

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/camera/FootageAudio.java:171`
- **What happens:** `loop()` starts with `openRecord()` → `openEncoder()` → `startRecording()` → `pump()`; only `pump()` checks `running` (`:281`). If `stop()` lands between `start()` spawning the thread and `loop()` running, the thread still acquires the mic and starts recording before its first `while (running)` test exits. `stop()`'s javadoc claims the thread "reads the chunk in hand (~21 ms) then cleans up", which assumes it is already pumping.
- **Evidence:** `start()` at `:142-150` sets `running = true` and spawns; `stop()` at `:153-155` only clears the flag; nothing at the top of `loop()` re-tests it. `stopAndWait()` joins at most `JOIN_MS` and then the caller releases the recorder while this thread still holds the mic.
- **Fix:** add `if (!running) { return; }` at the top of `FootageAudio.loop()`, before `openRecord()`.
- **Cost / risk:** none for the normal path (the flag is true there). Effect is a mic-privacy indicator blip and up to `JOIN_MS` of wasted work on a fast start/stop. No test covers it.
- **Test:** no unit test possible — needs device (AudioRecord).

## F-01.05 — Lane-transform retry has no stop condition (10 Hz forever if the view never sizes)

- **Grade:** single-source
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/camera/SingleCamera.java:585`
- **What happens:** when the `TextureView` has no size yet, the code re-posts `applyLaneTransform` after 100 ms and returns. There is no attempt limit and no cancellation on view detach, so a lane whose view never gets measured retries at 10 Hz for the life of the process — waking the main looper ten times a second on a head unit that is meant to idle.
- **Evidence:** the retry is unconditional inside the `width <= 0 || height <= 0` branch; nothing removes it (no `removeCallbacks` for `applyLaneTransform` anywhere in the file), and `applyLaneTransform` is also invoked from other paths (`:628`, `:646`, `:1556`, `:2597`) so several loops can run at once for one lane.
- **Fix:** bound it in `applyLaneTransform` (for example a retry counter, or rely on the existing `watchLaneLayout` size listener and post at most one retry).
- **Cost / risk:** small; if the size really arrives late the layout then waits for the size listener instead of the poll. No test covers it.
- **Test:** no unit test possible — needs device (TextureView layout).
