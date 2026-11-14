# Decisions — polish loop

One entry per decision that changed behaviour, with what it cost. Nothing here is a preference list.

## D-01 · A deliberate "no triggers" gets a stored marker (`none`)

**Finding:** `findings/00-alarm.md` F-00.09. `TriggerSet.value(∅)` returned `""`, and `parse("")`
means "corrupt → arm all six" (a safety net its own tests pin). So unchecking everything wrote all
six back and the dialog reopened fully checked — "report nothing" was not representable.

**Decision:** `value(∅)` now returns the marker `none`; `parse("none")` returns an empty set. `parse`
keeps the corrupt-value fallback (`""`, `null`, `???` → all six) so a damaged pref cannot silence
the alarm.

**Cost:** two test expectations change (`anEmptySelectionWritesAnEmptyString` → marker). Existing
installs that had `""` stored keep reading all six — that is what they behaved as before, so no
behaviour moves under anyone. The marker is a new string in the pref; anything that read the pref
raw (only the settings dialog and the blackbox/diagnostics JSON) now sees `none`.

## D-02 · Literal `'+bs+'n` in delivered captions → real newline

`SentryAlerts.deliver` (`:1012`) and `SentryAlerts.sendClip` (`:1176`) shipped the five characters
`'+bs+'n` where a `\n` was intended — a botched scripted edit of a Java string literal. Every
multi-lane send and every clip send carried it into Telegram. No decision to make, only a gate: the
literal is now gone from the package and a test guards it.

## D-03 · AlertPolicy becomes the single place that decides *and* reserves (pending)

Draft — see `findings/00-alarm.md` F-00.04 and F-00.05. Two threads (the encoder GL thread via
`MotionTap`, and the main looper via `Telemetry` listeners) call `decide`/`noteSent` today with no
lock, so both can read ALLOW before either records the send. And nothing records a *failed* send, so
a blocked chat re-runs snapshot → encode → upload on every trigger edge.

Draft shape: synchronise the policy; let the alert path reserve the slot atomically at decision time;
add a failure backoff distinct from the success cooldown. `aFailedSendDoesNotStartTheCooldown` pins
the current behaviour and will have to change — that is the cost, and it is a real one.

## D-04 · `floatOrNull(255f)` stays a sentinel — a real 255 km is indistinguishable

`findings/04-sound-profile-telemetry.md` F-04.03. The sentinel comes from the Lab note recorded in
`Signal`'s own comments (single-source): 255 / 254 / 253 / -1 / -65535 mean unknown. For a km-format
signal 255 is also a legal reading, so a car at exactly 255 km of range shows as "no data".

**Decision:** keep the behaviour, write the ambiguity down. Trading it the other way would make every
real "unknown" read as 255 km, which is the worse error. Cost: one display value, and only at exactly
255.

## D-05 · `segmentMs(0)` — fix the comment, not the code

F-04.07. Nothing stores 0 today, so honouring it would be a behaviour change no UI can trigger. The
comment stops promising "no segmentation"; when a UI grows a "no split" option, it picks a sentinel
that is not 0.

## D-06 · "Select all" keeps meaning everything — the dialog says what is hidden

F-07.02. Making select-all skip collapsed date sections would quietly redefine it. The delete path
already builds a confirm dialog; it gains the count of selected groups that live in collapsed
sections, so nothing gets deleted behind the user's back.

## D-07 · Recovery retries keep going, but slow down after 6

F-01.02. The javadoc already promises 6 fast retries then the normal cadence; the code never did it
and the write-stall referee stops watching after it fires once, so a dead encoder could be rebuilt
every 5 s indefinitely. Code moves to match the doc: 6 fast retries, then the normal segment
cadence — still retrying, just not at 10 Hz forever. Cost: recovery on a permanently broken encoder
takes up to a minute instead of 5 s.

## D-08 · Guard the two `startService` calls in `Recovery`; do not switch to foreground starts yet

F-07.06 (`unknown` grade). A refused background start aborts the rest of `restoreOnLaunch()` and the
blackbox then claims the overlays were restored. `QuickActions` already wraps the same call in
try/catch for exactly this reason, so logging and continuing is the house style. Switching to
`startForegroundService()` waits for one capture that actually shows the refusal.
