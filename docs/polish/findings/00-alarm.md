# Slice 00 — alarm (sentry-alerts)

Auditor: pi / claude. Date: 2026-11-15. Files read: 34 — all 19 files under `app/src/main/java/com/kooo/evcam/alarm/`, all 14 tests under `app/src/test/java/com/kooo/evcam/alarm/`, `docs/sentry-alerts-spec.md`, plus call sites in `settings/SettingsPreferenceFragment.java`, `AppConfig.java`, `telemetry/Telemetry.java`, `camera/EglSurfaceEncoder.java`, `blackbox/BlackBox.java`, `ZeekrShortcutApp.java`.

## F-00.01 — The telemetry callback runs on the main thread and does clock and bookkeeping work there

- **Grade:** measured
- **Severity:** P1 wrong behaviour in normal use
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/SentryAlerts.java:613`
- **What happens:** `onReadingsChanged` (`:689`) runs on the main looper — `Telemetry.java:632` posts every listener callback to `main` (`Telemetry.java:75`). On that thread it allocates a `Calendar` per alert (`quietNow`, `:871`), enters the `clock` lock (`time()` → `synchronized (clock)`, `:193`), writes `SharedPreferences` through `config.getAlertsArmDelayMinutes()` inside `armDelayPassed` (`:854`), and races unsynchronised fields (`lastHeartbeatAt` `:621`, `lastWatchNoteAt` `:828`) against the motion path on the GL thread.
- **Evidence:** Every `BlackBox.note` is cheap (its own `HandlerThread` + `LOCK`, `BlackBox.java:93-96`), so the remaining main-thread cost is the lock, the allocation and the prefs read — on a low-RAM head unit during motion bursts. `SentryAlerts.java:101` documents the lock as the fix for the cross-thread clock, which confirms the callback and the motion path run on different threads.
- **Fix:** Move `SentryAlerts.onMotion` onto the main executor, then drop the `clock` lock; or run `quietNow`/`armDelayPassed` off the main thread for the signal path too.
- **Cost / risk:** Touches ordering between signal edges and motion scores; `AlertPolicyTest` is single-threaded and unaffected.
- **Test:** no unit test possible — needs device

## F-00.02 — Delivered captions contain the literal `'+bs+'n` instead of a newline

- **Grade:** confirmed
- **Severity:** P1 wrong behaviour in normal use
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/SentryAlerts.java:1012` (same literal at `:1176`)
- **What happens:** The per-lane caption and the clip caption concatenate a literal `'+bs+'n` where a line break was intended, so Telegram shows `front'+bs+'n<caption>` as text. Every send with layout=separate and every clip send carries it.
- **Evidence:** Raw bytes at both lines are `"'+bs+'n"` (`cat -A`), not `'\n'`. Every other break in this package is a real newline (`TelegramRequest.java:112-124`). No test asserts caption text.
- **Fix:** Replace the literal with `'\n'` in `SentryAlerts.deliver` (`:1012`) and `SentryAlerts.sendClip` (`:1176`).
- **Cost / risk:** None. `AlertPictureTest` covers lanes and layout only.
- **Test:** Unit-test the caption string built for a multi-lane send (assert it contains `\n`, not `'+bs+'n`).

## F-00.03 — `pic` command is consumed, then dropped with no reply

- **Grade:** confirmed
- **Severity:** P1 wrong behaviour in normal use
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/SentryAlerts.java:1039`
- **What happens:** The poller runs without the sentry reading (deliberate, `:287` comment; spec §6.6). A `pic` command then reaches `deliver`, which returns without sending when `!sentryOn()` — and the command path passes `callback = null` (`:373`), so the owner gets neither photo nor reply. `status` still answers, which makes the silence look like a lost message.
- **Evidence:** `TelegramPoller.java:227` persists the offset before the handler runs, so the command is spent. Only a blackbox line records the drop.
- **Fix:** In `SentryAlerts.requestPictureInner`, pass a callback that replies, or skip the `sentryOn()` guard for command-triggered sends.
- **Cost / risk:** The guard is shared with alerts and the settings test button; scope the change to the command path.
- **Test:** no unit test possible — needs device

## F-00.04 — AlertPolicy state is mutated from the GL thread and the main thread with no lock

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/SentryAlerts.java:881`
- **What happens:** `onMotion` runs on the encoder GL thread and calls `policy.setParked/setArmed/decide/noteSent`. `onReadingsChanged` (`:689`) does the same on the main thread. Two triggers can both read ALLOW before either records the send, so one event sends twice and an hourly-cap increment is lost.
- **Evidence:** `Telemetry.java:632` posts listener callbacks to the main looper; `EglSurfaceEncoder.java:543` calls `MotionTap.get().submit(...)` inline on the GL thread, which calls the listener inline (`MotionTap.java:106`). `AlertPolicy` fields are plain (`AlertPolicy.java:45-52`); `triggers` (`SentryAlerts.java:118`) is plain too.
- **Fix:** Synchronise `AlertPolicy.decide`/`noteSent`/setters, or marshal `SentryAlerts.onMotion` onto the main executor.
- **Cost / risk:** `AlertPolicyTest`, `RequestPolicyTest` are single-threaded and unaffected.
- **Test:** Two-thread test: both threads call `decide` before either calls `noteSent`; assert only one ALLOW.

## F-00.05 — Failed sends are not throttled, so a blocked chat retries on every trigger

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/AlertPolicy.java:100`
- **What happens:** Cooldown and the hourly cap only advance in `noteSent`, which runs only after a successful send (`SentryAlerts.java:803`, `:902`). If the bot cannot post (kicked from the chat, blocked, Telegram 403), every trigger edge re-runs the whole path — snapshot, decode, re-encode, upload, retry — with no gap. The 10-minute throttle (`FAILURE_NOTIFY_MS`) limits only the local notification.
- **Evidence:** No failure counter anywhere in `AlertPolicy`; `decide()` reads only `lastSentAt`/`sentThisHour`. `sendPhoto` retries once on a dropped connection (`TelegramSender.java:166`), so each failed trigger costs up to two uploads.
- **Fix:** Count the attempt in `AlertPolicy` before firing, or add a separate failure backoff (say 5 min) distinct from the success cooldown.
- **Cost / risk:** `AlertPolicyTest` asserts current `noteSent` semantics; a new field needs its own cases.
- **Test:** Unit test: `decide` → allow, force failure (no `noteSent`), next `decide` must not allow inside the failure backoff.

## F-00.06 — Settings "find chat id" opens a second concurrent getUpdates

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/TelegramSender.java:421`
- **What happens:** `findChats` issues `getUpdates` from the sender thread while the poller is parked on a 25 s long poll. The package's own rule (`TelegramPoller.java:32`, spec §6.6) says one poller per bot; the second concurrent call can terminate the parked one, and the empty result then only has the `chatTitle` fallback to lean on.
- **Evidence:** `SentryAlerts.findChats` (`:662`) calls `TelegramSender.recentChats` with no offset and no pause of the poller. The 409 path pauses the poller 60 s (`TelegramPoller.java:49`, used at `:172`).
- **Fix:** Route the lookup through the poller, or pause the poller around `recentChats`.
- **Cost / risk:** Settings-only path; the fallback already exists, so severity depends on how often owners use the picker.
- **Test:** no unit test possible — needs device

## F-00.07 — A hand-typed group chat id answers nothing until an owner id is recorded

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/PollerGate.java:79`
- **What happens:** Sender binding exists — with an owner id recorded, private or group both work as long as the sender is that user (`:74-78`). Without it, only a private chat whose id equals the sender's is accepted, so the owner's own commands in a hand-typed group are refused with a blackbox line only.
- **Evidence:** `PollerGateTest.aGroupIsRefusedUntilTheOwnerIdIsKnown` pins this. `ownerId` is written only by the chat picker (`SettingsPreferenceFragment.java:1373`). The re-check at `PollerGate.java:83-86` is unreachable — `:79` already covers it.
- **Fix:** When the configured chat is a group and owner id is empty, record the first sender as owner (and say so), or refuse with a visible setting hint. Delete the unreachable branch.
- **Cost / risk:** Loosens a gate the test pins; update `PollerGateTest` with the chosen behaviour.
- **Test:** Extend `PollerGateTest`: group + owner id empty + owner sender → new expected verdict.

## F-00.08 — A fast off→on can leave two pollers; interrupt cannot break the blocking read

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/TelegramPoller.java:124`
- **What happens:** `stop()` nulls the field and gives up after `join(3_000)` (`:131`). `interrupt()` does not abort a blocking `HttpURLConnection` read, so the old thread stays parked up to its 25 s poll. The next enable sees `poller == null` and starts a second one, and the new one gets the 409 pause. It recovers, but only after a minute of deafness.
- **Evidence:** Code path read end-to-end; the 409 consequence rests on Telegram's documented one-poller rule, not on a measurement here.
- **Fix:** Keep the poller reference until its thread reports dead, and have `start()` reuse it instead of constructing a second.
- **Cost / risk:** `TelegramPoller` has no unit test today.
- **Test:** no unit test possible — needs device

## F-00.09 — "No triggers" cannot be stored: an empty selection is written back as all six

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/AppConfig.java:2221`
- **What happens:** The dialog writes `TriggerSet.value(picked)`, which is `""` for an empty selection; `setAlertsTriggers` feeds that straight back through `TriggerSet.parse`, whose empty fallback is all six. Unchecking everything therefore leaves all six armed, and the dialog reopens fully checked.
- **Evidence:** `TriggerSetTest.anEmptySelectionWritesAnEmptyString` plus `TriggerSetTest` pinning `parse(",,")` → all. `SentryAlerts.triggers` uses the same parse, so the engine matches the stored value, not the user's intent.
- **Fix:** Keep the "corrupt value" fallback but store an explicit marker for a deliberate empty selection (e.g. `none`) in `TriggerSet.parse`/`value`.
- **Cost / risk:** Touches the default documented in spec §1.5; `TriggerSetTest` needs the new case.
- **Test:** Unit test: `parse(value(emptySet))` must stay empty; `parse(garbage)` must stay all six.

## F-00.10 — Quiet hours read device wall-clock, so a DST change moves the window by an hour

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/alarm/SentryAlerts.java:871`
- **What happens:** `Calendar.getInstance()` uses the default zone, and `QuietHours` compares minutes-of-day only, so a fall-back makes the 22:00→07:00 window nine hours and a spring-forward five. Alerts are suppressed or allowed for an extra hour around the change.
- **Evidence:** `QuietHoursTest` has no DST case; the car runs where the zone has no DST in practice, so impact is limited to travel or a clock change.
- **Fix:** Note the wall-clock dependency in `QuietHours`, or compare against a fixed-zone minute instead of the device zone.
- **Cost / risk:** Low; existing tests unaffected.
- **Test:** Unit test in `QuietHoursTest` for a zone with a DST transition (fixed offset inputs).
