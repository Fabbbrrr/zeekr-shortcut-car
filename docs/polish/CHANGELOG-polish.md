# What shipped on `feat/fork-polish`

One entry per commit. Gate = `:app:assembleRelease` + `:app:testDebugUnitTest`, both offline, on lai.
Baseline before any change: build green, 168 test classes, 1873 tests, 0 failures.

| # | Commit | Area | Change | Why | Gate |
|---|---|---|---|---|---|
| 1 | `5ea7c059` | docs | audit method + measured baseline | the loop needs a written contract before it changes code | n/a |
| 2 | `9dcaca8d` | Alerts | `'+bs+'n` → real `\n` in `SentryAlerts.deliver` / `sendClip` | every multi-lane photo and every clip shipped those five characters into the Telegram caption | green (1874) |
| 3 | `6d40031b` | Alerts | `TriggerSet.value(∅)` returns the marker `none`; `parse("none")` → empty | "no triggers" was unrepresentable — unchecking all six wrote all six back; corrupt-value safety net kept | green (1874) |
| 4 | pending | docs | upstream queue, decisions, findings 00–07 | the record before the change | n/a |

## Implemented, awaiting a green gate (not committed yet)

**Alerts (our feature).** Motion decisions marshalled to the main looper and `AlertPolicy`
synchronised; the alert path now reserves the slot at decision time and a failed send opens a 5-minute
failure backoff (`aFailedSendDoesNotStartTheCooldown` rewritten to match — the agreed cost); a `pic`
command that cannot be answered answers instead of going silent; the settings chat-id lookup queues on
the poller so one `getUpdates` runs at a time; the unreachable `PollerGate` branch deleted and the
owner-id requirement stated; one poller instance held until its thread dies and reused (a fast
off→on used to start a second and go deaf for a minute); quiet hours documented as wall-clock with a
DST test. New tests: `AlertPolicyReserveRaceTest`, reserve/purity cases, `QuietHoursTest` DST case.

**Camera.** A dropped JPEG capture reports failure where it is dropped instead of waiting out the 5 s
save timeout; `jpegReader`/`jpegSize`/`jpegDropped` volatile; the no-size lane retry deduped and capped
at 300 attempts; recovery retries match their own javadoc (6 fast, then the normal cadence) with a new
ladder test; `FootageAudio.loop()` no longer opens the mic after a fast stop.

**Sound / profile.** A fading Run is tracked so a blocked write can be reclaimed; the after-failure gate
schedules its own retry instead of waiting for an unrelated signal; underruns still report at the buffer
cap; `anyGridEnabled` skips lanes the layout cannot place (a cabin-only recording no longer gets forced
onto the GL path); two stale comments fixed.

**Process lifecycle (root).** `AppLog.init` moved to the Application so the crash handler and
`warnings.log` arm in a process that never opens the main screen; hourly storage cleanup driven from the
process (it used to die with the main screen, so the photo quota stopped being enforced while recording
ran); the image-adjust overlay is dismissed on activity destroy.

**Floating button.** Bind bookkeeping records that a bind was *requested*, so a quick create/destroy
cannot leave a connection against a dead service; `ACTION_CANCEL` handled so a cancelled drag cannot eat
the next tap.

**Vehicle / diagnostics.** Reap the `logcat` process the diagnostics report spawns; `isDestroyed()`
guards on the diagnostics screen's posted lambdas.

**Recovery / overlay.** Guarded `startService` calls (log and continue) so one refusal cannot cancel the
rest of the restore, and the blackbox names what was refused; the screen-off flag clears only after a
successful launch, so a failed one retries. New test drives the flag with a context that refuses
`startActivity`.

**Playback / share.** Audio focus is unregistered whenever it was ever granted (a permanent loss used to
leave the listener, the activity and its views reachable); the delete-confirm dialog states how many
selected groups sit in collapsed days; the dead portrait branch in photo playback is gone. New test pins
select-all across a collapsed section.

**Settings / UI.** (in flight)

## Commit map (gates green on this exact tree: 1898 tests, 0 failures; assembleRelease OK)

| Commit | Area |
|---|---|
| `135a390b` | Alerts — motion decision on main, synchronised policy, reserve-at-decision, 5-min failure backoff |
| `d0588cc7` | Alerts — one poller per bot, chat lookup queued on it, dead PollerGate branch, DST test |
| `f3e256bb` | Camera — dropped capture reported, volatile fields, bounded lane retry, recovery ladder per javadoc |
| `9d1229b3` | Sound — Run tracking, failure-gate retry, underrun reporting at cap; profile — grid rule |
| `e171c3e8` | Process — crash handler, warnings log, hourly cleanup, overlay dismiss |
| `0aa98529` | Recovery — guarded service starts, flag clears only on success (carries the floating-button `ACTION_CANCEL` + bind fix too) |
| `59f82639` | Playback — audio focus released, select-all says what is hidden, dead portrait branch gone |
| `779a4f53` | Settings — live header state, cached characteristics, hit height, stale comment |

Two honest notes. The floating-button fix rode inside `0aa98529` rather than its own commit. And three
`docs(ntfy)` commits (`e2b184a6`, `b9862e15`, `5151f18f`) are another session's work, committed into this
same checkout while the loop ran — they are on this branch, so pushing the branch pushes them too.
