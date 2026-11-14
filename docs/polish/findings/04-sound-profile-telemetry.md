# Slice 04 — sound / profile / telemetry

Auditor: pi (claude). Date: 2026-11-15. Files read: 60 in scope — read in full or in the cited
region: `sound/DriveSound.java`, `sound/SoundOutput.java`, `sound/VirtualEngine.java`,
`sound/PulseVoice.java`, `sound/PulseProfile.java`, `sound/EngineVoice.java`,
`sound/OutsideVoice.java`, `sound/DriveSoundRules.java`, `sound/OutputProbe.java`,
`profile/ProfileStore.java`, `profile/RecordSpecs.java`, `profile/StreamSpec.java`,
`profile/ProfileSizes.java`, `profile/ProfileMigration.java`, `profile/ProfileValidation.java`,
`profile/QualityPreset.java`, `profile/Profile.java`, `profile/LaneLayout.java`,
`telemetry/EcarxSource.java`, `telemetry/Telemetry.java`, `telemetry/Signal.java`,
`telemetry/MirrorLampSource.java`, `telemetry/LocationSource.java`, `telemetry/DarkScreenProbes.java`,
`telemetry/InfoBarLayout.java`, `telemetry/InfoBarRenderer.java`, `telemetry/VehicleStateMapper.java`.
The rest were grepped for threads, allocation, locks and dead calls.

## F-04.01 — anyGridEnabled trusts the default its own sibling method refuses to trust

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/profile/RecordSpecs.java:173` vs `:62`; caller `app/src/main/java/com/kooo/evcam/AppConfig.java:528`
- **What happens:** `defaultRecord()` gives every camera `grid = true`, including the two cabin
  cameras. `storedAsGrid()` says out loud that for those roles the value "is a default that means
  nothing, so you cannot just look at it" and returns false for them. `anyGridEnabled()` looks at
  exactly that field for every enabled camera, so a cabin-only recording reports grid on, and
  `shouldUseCodecRecording()` forces the MediaCodec/GL path and overrides a MediaRecorder choice —
  then logs "已选四宫格录制", which nobody chose.
- **Evidence:** `RecordSpecs.java:173` (`camera.record.grid` for any enabled camera) against
  `RecordSpecs.java:62-69`; no test references `anyGridEnabled` (grep over `app/src/test`: 0 hits).
- **Fix:** in `RecordSpecs.anyGridEnabled()`, skip cameras the lane layout cannot place — the same
  rule `storedAsGrid()` uses — instead of reading `grid` raw.
- **Cost / risk:** changes which encoder path a cabin-only recording takes (GL → MediaRecorder). The
  GL path is the safe direction, so the risk is lost repacking if a cabin lane ever does get repacked.

## F-04.02 — a fading Run is dropped without tracking it; a blocked write strands it and its AudioTrack

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/sound/DriveSound.java:182` (restart path) and `:195`
- **What happens:** on a style / view / output change the old Run is stopped and set to null without
  going into `leaving`, and a new Run starts in the same call. If the old thread is stuck inside
  `track.write(..., WRITE_BLOCKING)` it never reaches its `finally { close() }`, so its AudioTrack
  stays open on the same bus as the new one. Nothing counts or caps Runs, so each change can add
  another pair. A failing Run that is no longer in `run` or `leaving` also reports to nobody, so its
  failure never sets `failedAtMs`.
- **Evidence:** mechanism read from code (`DriveSound.java:182-196`, `SoundOutput.java:108`); the
  stall itself is the one the file documents — `getTimestamp` reads ~5.1 s whatever the buffer. Not
  reproduced. Proof would be a device run that toggles style during a stall and counts open tracks.
- **Fix:** keep the fading Run in `leaving` on the restart path so `hurry()` can reclaim it, and skip
  the restart (or defer it) while more than one Run is still alive.
- **Cost / risk:** a style change would land up to `FADE_SEC` late. `DriveSoundRules` tests do not
  cover the Run lifecycle.

## F-04.03 — the 255 "no data" code eats a legitimate 255 km

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/telemetry/Signal.java:435`
- **What happens:** `floatOrNull()` maps 255 and -65535 to null for every float signal. For
  `RANGE` and `ODOMETER` (both `Format.KM`) 255 is a legal reading, so a car showing exactly 255 km
  of range displays as "no data" on the info bar and the panel.
- **Evidence:** `SignalDecodeTest.java:41` pins `floatOrNull(255f) == null`; the sentinel itself comes
  from the Lab note in the `Signal` comments (single-source). Every other float signal cannot reach
  255 in its unit, so km formats are the only real collision.
- **Fix:** apply the sentinel in `Signal.decode()` only for formats whose legal range excludes it, or
  compare the raw code before the format converts it.
- **Cost / risk:** one display value. Existing test asserts the current behaviour and must change.

## F-04.04 — the retry-after-failure gate has no timer behind it

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/sound/DriveSound.java:187`
- **What happens:** after a Run reports a failure, `update()` returns at the `failedAtMs` gate and
  schedules nothing. `update()` only runs on install, on `refresh()`, or when a telemetry reading
  changes. So after a transient failure the sound stays off until some unrelated signal moves — on a
  parked, silent car that can be indefinitely.
- **Evidence:** `DriveSound.java:187-189` (bare `return`), `RETRY_AFTER_FAILURE_MS = 30_000` at
  `DriveSound.java:70`; no `postDelayed` anywhere in the file's control path.
- **Fix:** in `DriveSound.update()`, when the gate blocks, `main.postDelayed(this::update, remaining)`
  once per gate window.
- **Cost / risk:** one extra main-thread tick per failure. Deliberate "don't re-open a missing
  speaker" behaviour still holds; it just now actually retries.

## F-04.05 — underruns stop being reported once the buffer hits its cap

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/sound/SoundOutput.java:121`
- **What happens:** `watch()` returns true only when the buffer actually grew. Once `bufferFrames`
  reaches `MAX_BUFFER_MS` (120 ms) the underrun counter keeps rising but nothing is returned and
  nothing is logged, so audio crackling at the cap leaves no trace in the blackbox — the one case
  where the caller can no longer fix it by hand is the case that goes silent.
- **Evidence:** `SoundOutput.java:34-36` (`START 30 / GROW 10 / MAX 120`) and `:124-129` (`grown` only
  inside the growth branch); `DriveSound` logs "欠载，缓冲加大" only when `watch()` returns true.
- **Fix:** in `SoundOutput.watch()`, log once per increase whenever `count > underruns`, growth or not.
- **Cost / risk:** extra blackbox lines on a noisy bus. Cap the rate if it floods.

## F-04.06 — two documents still say the fast read runs every 50 ms; it runs every 20 ms

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/sound/DriveSound.java:41` and
  `app/src/main/java/com/kooo/evcam/telemetry/Telemetry.java:206`
- **What happens:** both comments describe a 50 ms fast-read cadence. `DriveSound.FAST_MS` is 20 and
  `FastReads.MIN_INTERVAL_MS` is 20, and the 20 ms came from a Lab measurement of per-read cost — so
  the running rate is 2.5× what the text promises.
- **Evidence:** `DriveSound.java:60`, `FastReads` floor; the comments are the only 50 ms left.
- **Fix:** update both comments to 20 ms.
- **Cost / risk:** none.
- **Test:** no unit test possible — copy change.

## F-04.07 — `segmentMinutes = 0` is documented as "no segmentation" but means one minute

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/profile/StreamSpec.java:67` vs
  `app/src/main/java/com/kooo/evcam/profile/RecordSpecs.java:270`
- **What happens:** the field comment says 0 means no segmentation; `segmentMs(0)` falls through to
  `DEFAULT_SEGMENT_MINUTES`, so a stored 0 segments at one minute — the shortest interval available,
  the opposite of never.
- **Evidence:** the two lines above; every caller passes the value straight through
  (`MultiCameraManager.java:4423`, `AutoLock.java:516`, `RecordingFloatingService.java:634`).
- **Fix:** either honour 0 in `RecordSpecs.segmentMs()` (return `Long.MAX_VALUE` / a no-segment
  sentinel) or delete the comment; picking one is a product call.
- **Cost / risk:** the setter currently never stores 0, so nothing breaks today; the trap fires the
  first time a UI offers "no split".

## F-04.08 — the horn cell on the vehicle-status panel can never light

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/telemetry/InfoBarLayout.java:123` and
  `app/src/main/java/com/kooo/evcam/telemetry/InfoBarRenderer.java:386`
- **What happens:** `VehicleState.horn` has no writer — `Builder.horn(...)` has zero call sites and
  `VehicleStateMapper` never sets it — so `drawHorn()` always receives null and the panel row always
  renders "no data". The layout comment records it as a Lab gap, but the panel still shows the cell.
- **Evidence:** grep across `app/src/main`: one reader (`InfoBarRenderer.java:387`), no writer.
- **Fix:** drop `Cell.HORN` from `PANEL_ROWS` until a source exists, or keep it and mark it
  explicitly as "no source yet" in the renderer so it does not read as a broken sensor.
- **Cost / risk:** one panel row disappears. No test covers panel rows.
