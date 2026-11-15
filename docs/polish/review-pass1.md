# Polish branch review — pass 1 (narrow, adversarial)

Scope: 135a390b, d0588cc7, e171c3e8, 0aa98529, 59f82639 (`git show <sha> -- app/src`). Question per commit: does the change fix the mechanism or only the described case? What does it break? Any test testing its own fixture? Grades: blocker / should-fix / nitpick / correct-as-is.

## 135a390b — Alerts: main-looper decision, reserve-at-decision, failure backoff

Mechanism: yes, fixes the mechanism. `reserve()` does decision+slot-take under one monitor (`AlertPolicy.java` synchronized block `reserve` → `noteSentLocked`), which closes the two-thread double-ALLOW for every caller, not just the described pair. `noteFailure` → `FAILURE_BACKOFF` stops the snapshot/encode/upload storm on a blocked chat at the decision gate, before any work is spent — correct place.

- **Should-fix:** 135a390b alone does not compile — `SentryAlerts.java:682` calls `p.runBetweenPolls(...)`, which is only added to `TelegramPoller` in the *next* commit (d0588cc7). Bisect lands on a broken commit. Fine at branch tip, but the two commits are one unit.
- **Should-fix (documented cost, confirming it's real):** a failed send now both consumes an hourly-cap slot (`reserve` counts it) and opens 5 min silence. Failed sends are charged against `MAX_PER_HOUR` even though nothing delivered. The rewritten test pins this (`aFailedSendOpensTheFailureBackoff`), matching D-12; fine, but the settings display of `sentThisHour` now counts ghost sends.
- Thread affinity: check passes. Motion decision marshalled to main (`SentryAlerts.java:204-207`); `SnapshotTaker.take` is documented callable from any thread (`SnapshotTaker.java:72`); `noteFailure` firing on the sender thread is safe because all policy mutators are synchronized — the class comment in this commit says exactly that.
- Callers of the inverted callback (`onDelivered` → `onSendFailure`) audited at this commit: the non-alert paths pass `null` (SentryAlerts lines 388, 449, 1129); only the two alert paths wire `this::noteSendFailure`. No caller kept the old success meaning. Good.
- `decideLocked` ordering (FAILURE_BACKOFF after HOURLY_CAP, before COOLDOWN) — report-only ordering, no behavioural trap; backoff boundary test (`decide` at `+FAILURE_BACKOFF_MS` exactly → ALLOW) matches the `nowMs < failureUntilMs` comparison.
- Tests: `AlertPolicyReserveRaceTest` is a real mutual-exclusion test (deterministic result either way, tests the lock, not a fixture). Rewritten `aFailedSendOpensTheFailureBackoff` tests the mechanism, not its own setup — the reserve→failure→backoff sequence is the production path. `decideStaysAPureCheck` correctly pins the diagnostics contract.
- **Nitpick:** `cooldownLeftMs()` reports only the success cooldown; during failure backoff it returns 0 while `decide()` says FAILURE_BACKOFF, so any UI reading it under-reports the silence.

## d0588cc7 — Alerts: one poller per bot

**Blocker — the headline case is not fixed, only made quiet.** Fast off→on: `start()` sees the old thread alive, sets `restartWanted=true`, returns (`TelegramPoller.java:113-127` at this commit). When the old thread lands, the loop enters on `restartWanted`, drains the queue, then `if (!running) { restartWanted = false; return; }` and **exits — polling never resumes**. The duplicate-poller/409 is gone, but the toggle now silently leaves no poller. The flag needs to set `running` (or the loop needs a `restartWanted` branch that continues instead of returning). This is exactly the described scenario (off→on within one 25 s read); it is a mechanism fix for the duplicate but not for the restart.

- Reference-holding in `stop()` (`TelegramPoller.java` stop, `!stopping.isAlive()` → keep ref) is correct and the stop log moved to the real-death branch — honest logging fix.
- `runBetweenPolls` queue: correct mechanism (tasks run in the gap after a read lands, before the next `getUpdates`); exceptions caught per task. `OutOfMemoryError` in the catch is odd but harmless.
- PollerGate: the removed branch was genuinely unreachable — the surviving `else if` (`PollerGate.java:83-86` at this commit) admits exactly private-self when owner is unrecorded, which is the only input the dead branch could match. Removal verified, not just asserted.
- **Test testing its fixture:** `QuietHoursTest.aDstChangeMovesTheWindowByOneRealHour` — the `assertEquals(60, ...)` asserts JDK `OffsetDateTime` offset arithmetic, and both `assertTrue`s pass on the pre-existing window logic (both minutes are inside 22:00–07:00). It cannot fail on any `QuietHours` change short of rejecting in-window minutes. It documents D-10; it does not pin mechanism. Should-fix (as a test, not behaviour — the behaviour itself is an accepted decision).

## e171c3e8 — Process: init outlives the main screen

**Correct-as-is.** The mechanism is fixed, not the case: idempotent `AppLog.init` (`AppLog.java` `sInitialized` under `LOCK`) protects every caller — the first call (ContentProvider or Application, whichever lands first) does the real work, all later calls no-op, including the retained `MainActivity.java:291` call. Previously *every* call rotated the session log and re-armed the handler, so this fixes a live defect, not just the described path. `StorageCleanupManager` moved to `ZeekrShortcutApp.onCreate` — its deletions run under `FootageLocks.guard()` (`StorageCleanupManager.java:140`) and its handler is the main looper (`:60`), so moving its owner Activity→Application changes lifetime only, not thread affinity. Gallery/playback honour the same locks, so always-on cleanup cannot delete a file mid-view.

- `imageAdjustFloatingWindow.dismiss()` in `onDestroy`: correct fix for a window outliving its host context. Note the stated behaviour change: dismiss persists unsaved sliders (same path as the close button) — deliberate and commented, but it means an Activity destroy (e.g. config change) now writes config where it previously just leaked.
- Tests: `AppLogInitTest` tests the mechanism (first call really renames current→previous and arms handler; second call leaves both alone) — real assertions, not fixture-only. Reflective reset of `sInitialized`/`sAppContext` with save/restore is the right way to handle Robolectric shared statics; the `assertNotNull(handler)` half of test 1 is weaker (could pass on a harness-installed handler) but the rename assertion carries it.

## 0aa98529 — Recovery: one refused start cannot cancel the rest

Partly mechanism, partly the described case.

- **Should-fix:** the guard covers two of three overlays. `RearViewMirrorService.start` is still a bare `startService` (`RearViewMirrorService.java:104`) called from a `postDelayed` lambda in `restoreOnLaunch` — a refused start there is an uncaught exception on the main looper, which is exactly the failure this commit says cannot cancel the rest, and it can never report itself into the refused list. If it fires first, it still takes everything down with it.
- Recovery flag fix is a real mechanism fix: `setUiLeftForScreenOff(false)` moved after a *successful* `startActivity` (`Recovery.java`, `bringBackUiAfterScreenOn`); a throw now leaves flag and dedupe window intact so the next screen-on/keepalive pass retries. The `StallWatch.isForeground()` branch still clears deliberately, as before and as tested.
- `RecordingFloatingService`: `bindRequested` replacing `isServiceBound` is correct — a bind whose callback never arrives no longer leaks a binding that pins `CameraRecordingService` alive; unbind wrapped for the never-established case. Moving the bind to main is safe (callbacks were already posted to main). `ACTION_CANCEL` resets long-press/drag state — genuine fix for a cancelled gesture eating the next click.
- `DiagnosticsActivity` `isDestroyed()` guards and the logcat child-process `destroy()/waitFor()` in `finally` are correct.
- Tests: `RecoveryScreenOffFlagTest` pins the reorder (old code fails `aRefusedLaunchLeavesEverythingForTheNextPass`) and the intentional clear branch — real mechanism tests. `ThrowingContext` is a stub of the OS refusing, not of the code under test; acceptable.

## 59f82639 — Playback: audio focus release, select-all notice

**Correct-as-is.**

- `everGranted` fixes the mechanism: focus ever granted then taken long-term by another app still gets de-registered on leaving playback (`PlaybackAudioFocus.java`, `abandon()`); previously the registration stayed on the system forever. Grant/reset pairing is per-instance and correct.
- `getSelectedCountInCollapsedSections` + delete-dialog line: correct — select-all has always included collapsed sections; the dialog now says so instead of hiding it. Plurals added in all three locale files; `values-ms` keeping `other`-only matches the existing file's convention.
- Dead-code claim verified: `PhotoPlaybackActivity` is `android:screenOrientation="landscape"` in `AndroidManifest.xml:151`, so the removed portrait GridLayoutManager branch was unreachable. `status_bar_height` dimen exists (`dimens.xml:11`, 48dp) — tap-target consistency, fine.
- `ExpandablePhotoGroupAdapterSelectAllTest` tests the mechanism (selectAll ignoring expansion state, plus the new hidden count), not its fixture — the collapse-default assertions would fail if the adapter started honouring expansion state.

## Verdict

**HOLD.** Blockers: d0588cc7 `TelegramPoller` restart path consumes `restartWanted` and exits without resuming polling — the exact fast off→on case the commit claims to fix ends with no poller. Should-fix before push: guard `RearViewMirrorService.start` in `restoreOnLaunch` (or justify why that one cannot be refused); land 135a390b+d0588cc7 as one unit (135a390b alone does not compile); drop or strengthen the `QuietHoursTest` DST test, which only exercises JDK offset arithmetic.
