# Climate alerts — the case, the recipe, the shortcomings

Written 2026-11-16, on top of the alerts branch (`feat/parked-alerts-pr`, head `76b98bb4`) with the
polish branch (`feat/fork-polish`, `891bd24a`) read for the settings shell. Docs only: this lives on
branch `docs/climate-alerts`. No code, nothing to run.

The alerts branch re-sliced itself into `alarm/{guard,media,ntfy,policy,telegram}` after the first
pass; the citations below are against `76b98bb4`.

The ask: the car tells me when the cabin is too cold or too warm; I answer in the chat with
`AC ON` and a target temperature; optionally the seat heat or seat ventilation comes with it,
picked from the temperature delta — and never heat a seat when it is hot outside. Plus: the
settings screen is a wall of rows, collapse it.

Grades follow AGENTS.md: **confirmed** (two independent sources), **single-source**, **measured**
(read off the running thing), **unknown**. Every runtime fact below carries one.

---

## 1. Verdict

**Yes — alert that it is too hot or too cold is cheap and safe.** Everything it needs already
ships: the cabin and outside temperature signals are confirmed on this car, the alert pipeline,
the chat command parser and the transports all exist. New code is a policy class, a command verb
and a settings page. No foundation changes.

**`AC ON` from the chat is the part with a real question mark.** The write primitive exists
(`HVAC_FUNC_POWER`), and the temperature setpoint does exist in the API, but:

- the setpoint must go through a **float** channel the app does not use yet (one new reflected
  method), and the integer channel is refused by the vendor in so many words;
- whether a **locked, parked 7X accepts an HVAC power-on from the local API** is **unmeasured**.
  Remote climate from the Zeekr phone app proves the car *can* run HVAC while parked; it does not
  prove the head unit can ask for it while the vehicle is "off".

So the honest split is: **alert-only ships now; the write ships after one 20-minute test on the
car.** If that test fails, the feature degrades to "the car tells you it is 34 °C, you open the
Zeekr app and press the button" — still worth having, and no code is wasted, because the alert,
the thresholds and the settings are the same either way.

**Seat heat/ventilation:** the writes are confirmed **on this car** (the smart buttons use them
daily), so wiring them to the same command is cheap. But switching a **seat heater on with nobody
in the car** is the one part of this design I would not ship automatically — see §8.

---

## 2. What already exists (no new plumbing)

| Thing | Where | Grade |
|---|---|---|
| Cabin temperature `TEMP_INSIDE` = `0x00100C00`, °C | `telemetry/Signal.java:134` | confirmed (shown in the info bar daily) |
| Outside temperature `TEMP_OUTSIDE` = `0x00100B00` | `Signal.java:133` | confirmed |
| State of charge `BATTERY` = `0x00404000` | `Signal.java:131` | confirmed |
| Seat occupation `SEAT_DRIVER…` | `Signal.java` (BODY group) | confirmed |
| Parked test (`!driving(gear, speed)`) | `alarm/SentryAlerts.java:1886` | confirmed (rides the whole alerts branch) |
| "Sentry on = head unit awake" | `SentryAlerts.java:1906` comment, platform notes §3.6 | measured on the car |
| Alert gates: cooldown, hourly cap, quiet hours, arm delay | `alarm/policy/AlertPolicy.java`, `policy/QuietHours`, `policy/ArmDelay` | confirmed by the alerts unit tests |
| Chat pause (`alerts off` / `alerts on`) | `alarm/telegram/TelegramCommand.AlertsState`, `AppConfig.isAlertsPaused()` | confirmed (on this branch) |
| Chat parse (pure) | `alarm/telegram/TelegramCommand.java:292` `parseText` | confirmed |
| Chat send + buttons + single-use tokens | `alarm/telegram/TelegramSender`, `alarm/AlertButton`, `alarm/guard/GuardToken` | confirmed on the car |
| Command authority (owner chat only) | `alarm/telegram/PollerGate.java`, `TelegramCommand.fromOwner` | confirmed |
| Writes with readback-until-changed | `button/CarMoves.java:272` `set`, `:292` readback loop | confirmed |
| HVAC power write (front zone) | `button/ButtonAction.java:162` `CarMoves.toggle("IHvac","HVAC_FUNC_POWER",ROW_1)` | **unverified on the 7X front unit**; rear power wrote and read back on an AU 7X (`docs/australian-7x-rear-ac-findings.md`) |
| Seat heat / vent writes (1–3 levels) | `button/ButtonAction.java:144`, `CarMoves.levels` | **confirmed on this car** (used daily) |
| Function-id lookup by constant **name**, not number | `telemetry/CarNames.java:65` `function(iface, field)` | confirmed |
| Two transports, one command vocabulary | `alarm/TelegramChannel`, `alarm/ntfy/NtfyChannel`; `alarm/ntfy/NtfyCommandPoller` reuses `parseText` | confirmed |
| Settings two-pane shell (left = sections from XML, right = one section) | `settings/SettingsShellFragment.java:131` `showSection` | confirmed |

Consequence: **detection needs zero new reading code.** `onReadingsChanged` already receives
`Readings`, and `readings.number(Signal.TEMP_INSIDE)` is the cabin temperature.

---

## 3. The write recipe, and what it costs

### 3.1 Temperature setpoint

Evidence is from an ECARX head unit of the same API family (Lynk & Co, `Bigcaibird/cardesk`,
commits up to 2026-09-18). Their vendor adapter is quote-level unambiguous:

- `IHvac.HVAC_FUNC_TEMP = 0x10060100`, `HVAC_FUNC_TEMP_DUAL = 0x10060500`,
  `HVAC_FUNC_TEMP_MIN/MAX/STEP/UNIT = 0x10060300 / 0x10060200 / 0x10060400 / 0x10060600`
  (`IHvac.smali` from `maxim-saplin/zeekr_apk_mod`, 6.0.5). Grade: **single-source** (constant
  table from a shipped APK, same family).
- Integer write is **refused by the vendor**:
  `"Set function by integer is not supported for IHvac.HVAC_FUNC_TEMP, please use setCustomizeFunctionValue"`
  (`CarFunctionValueImpl.java:1137`, quoted in `AdaptapiGateway.kt:345`). Grade: **single-source**.
- The working call is `setCustomizeFunctionValue(int func, int zone, float celsius)`, and the zone
  **must** be non-zero (1 = driver, 4 = passenger); zone 0 is rejected as "not a valid zone".
  Read back with `getCustomizeFunctionValue(func, zone)` returning a **Float in °C**. Grade:
  **single-source**.
- Range and step come from the vendor source: `TEMP_MIN 16.0f`, `TEMP_MAX 31.0f`, `STEP 0.5f`
  (`CarFunctionCustomizedValueImpl.java:33`). **Do not hardcode these** — read
  `0x10060200 / 0x10060300 / 0x10060400` at runtime and refuse to write if any of them is unreadable
  or disagrees with the configured value. That is the no-guess rule, enforced in code.

Our side: `EcarxSource` looks up only `getFunctionValue(int[,int])` and
`setFunctionValue(int[,int],int)` (`EcarxSource.java:476-478`, write at `:1478`). So the new work is
**two extra reflected methods plus one float read**, ~80 lines, plus a decode test.

### 3.2 Power, AC, AUTO, seats

| Function | Id | Channel | Grade |
|---|---|---|---|
| `HVAC_FUNC_POWER` | `0x10010100` | int, zone `ROW_1_ALL = 0x08` | read confirmed on an AU 7X; **write untested** on the 7X front unit (rear wrote fine) |
| `HVAC_FUNC_AUTO` | `0x10010200` | int, zone | single-source (constants), untested here |
| `HVAC_FUNC_AC` | `0x10010300` | int, zone | single-source, untested here |
| `HVAC_FUNC_SEAT_HEATING` | `0x10050200` | int + level `1..3` (auto `…0f`) | **confirmed on this car** |
| `HVAC_FUNC_SEAT_VENTILATION` | `0x10050100` | int + level `1..3` | **confirmed on this car** |
| `HVAC_FUNC_OVERHEAT_PROTECTION` | `0x10080400` | int | single-source, **presence on the 7X unknown** |
| `HVAC_FUNC_PRE_CLIMATISATION` | `0x100a0100` | int | single-source, unknown |
| `HVAC_FUNC_TEMP_UNIT` | `0x10060600` (`C = …01`, `F = …02`) | int | single-source, unknown on this car |

Two facts that matter more than the ids:

1. **Acceptance is not control.** The AU 7X report is explicit: the setter returned `true` and the
   factory screen had to confirm. Every climate write needs a readback, which `CarMoves.set()`
   already does.
2. **A climate power cycle resets things.** Measured on an AU 7X (`docs/australian-7x-recirculation-findings.md` §1):
   switching climate off and on resets recirculation to AUTO and leaves the rear zone off. So
   `AC ON` can silently change a rear passenger's comfort. Either restore those two after power-on
   or say it in the reply.
3. **Zone semantics differ per function.** Front *power* is the row-one ALL scope `0x08`; front
   *temperature* is per seat (`0x01` / `0x04`) because the car is dual-zone. Reading the default
   zone for power returns `0` whether climate is on or off (`australian-7x-recirculation-findings.md` §2) —
   that is a trap, not a signal.

### 3.3 The wake question

`SentryAlerts` already knows the head unit only stays awake while sentry mode is on
(`sentryOn()` at `:1793`). Climate alerts ride the same window for **reading**:

> **Sentry off = car asleep = we cannot read the cabin, so there is nothing to alert on.**

The reply half of that sentence did not survive the branch: `SentryAlerts.syncPoller()` no longer
requires sentry mode at all (corrected in §7 item 5). Reading still needs an awake head unit, so
the climate page must say the reading half, in the same words the existing `alert_cmd_no_sentry`
string uses.

---

## 4. Detection: the policy

Pure class, `alarm/policy/ClimatePolicy`, same shape as `AlertPolicy` (no Android, unit-tested).

Inputs per check: cabin °C, outside °C, SoC, parked, sentry, HVAC power state (new read),
seat occupancy, quiet-hours state, minutes since the last climate alert.

| Tier | When | Quiet hours | Repeat |
|---|---|---|---|
| **Comfort hot** | parked, HVAC **off**, cabin ≥ `hotLimit` (default 30 °C), held for ≥ 10 min of fresh samples | respected | once per park session; re-arms 3 °C below the limit |
| **Comfort cold** | same, cabin ≤ `coldLimit` (default 5 °C) | respected | same |
| **Safety** | parked, cabin ≥ 35 °C **and** a seat occupied (`SEAT_*` ≠ empty/unknown), or cabin ≥ 40 °C regardless | **bypassed** | every 15 min while true, max 3 |

Design rules that are not negotiable:

- **Fresh samples only.** `SignalAges` records *changes*, so a steady 31 °C cabin ages forever and
  looks stale. Track "last non-null cabin reading received" in the policy itself. If no reading
  arrived for 10 min, say nothing (the car is asleep, we do not know).
- **No alert while driving.** Parked only, same rule as sentry alerts.
- **`alerts off` silences climate too.** The branch already has a chat pause (`AppConfig.isAlertsPaused()`);
  a paused owner must not get climate messages either, and the safety tier does not override an explicit pause
  (only quiet hours). One flag, no new state.
- **No alert while HVAC is on** in the comfort tier; a cabin over 40 °C *with* the AC on is a
  different fault and belongs in v2.
- **Message shape:** one line of text, no picture. `Cabin 33 °C, outside 29 °C, AC off. Reply AC ON
  to cool it to 22 °C.` Text costs nothing against the 100 kB budget. Optional: the four-camera
  picture if the owner wants proof of *why* (a window down) — v2.
- **Buttons:** `AC 20 / AC 22 / AC 24 / Off` as inline buttons on the message, single-use token via
  the existing `GuardToken` mechanism so a week-old message cannot fire a 3 kW heater.

State is per park session: first drive after an alert clears it.

---

## 5. The chat side

`alarm/telegram/TelegramCommand` stays the single vocabulary (ntfy reuses `parseText`, so both
transports get it free). It has already grown twice on this branch — `alerts off/on` and
`sens` — so a new verb follows a shape the tests and the help text already know. New verb, no state
machine:

| Message | Meaning |
|---|---|
| `ac` | one line: cabin, outside, setpoint, climate on/off, seat state |
| `ac on` | default target from settings (22 °C), 10 min, AUTO on, seats per §6 |
| `ac on 23` / `ac 23` | same, target 23 °C |
| `ac off` | power off + seats off |

Why no two-step "send AC ON, then send the temperature": a bot that waits for a second message is
a state machine that breaks on restarts, on a second device, and on the owner changing their mind.
`ac 23` is one line, and the buttons cover the common choice. This is the same reasoning that made
`guard auto then` a single verb.

Reply is a receipt, not a hope: `AC on, target 23 °C, cabin 31 °C. Off at 14:12 unless you say ac off.`
The "off at" needs a scheduled write; the app already owns a scheduler for the clip delay
(`SentryAlerts:263`), so reuse that shape.

Bounds and guards, all in the policy:
- never write while `gear != P` or while moving (`CarMoves.standstill()` exists);
- refuse to start below a SoC floor (default 20 %);
- refuse if the cabin is already within 1.5 °C of the target and HVAC is on;
- hard cap 10 min per command, and re-arm only on a fresh command.

---

## 6. Seat heat and ventilation from the delta

Cabin `C`, target `T` (default 22 °C), deadband `D` (default 1.5 °C), outside `O`:

| Condition | Seat action |
|---|---|
| `C < T − D` **and** `O < 20 °C` | heat, level from settings (default 2) |
| `C > T + D` **and** `O > 20 °C` | ventilate, level from settings (default 2) |
| `O ≥ 20 °C` and cabin cold | **nothing** — a cold cabin on a warm day means the AC has been running; heating the seat is uncomfortable and wastes power |
| `O ≤ 10 °C` and cabin hot | nothing (symmetric rule; happens after a heater cycle) |
| otherwise | nothing |

The outside temperature is the veto, exactly as asked: **no seat heat when it is hot outside**, no
matter how cold the cabin reads. The delta picks the level, the outside temperature decides whether
the action is allowed at all.

Driver seat only by default; passenger and rear are separate switches. Seat heat never starts from
an *automatic* path (see §8).

---

## 7. Shortcomings, ranked by how much they could hurt

1. **HVAC while locked is unmeasured.** The single biggest unknown. If the 7X refuses the write
   from a sleeping vehicle, `AC ON` becomes "AC when you next get in". Mitigation: one test before
   any code is written against it (§9, test 2). Cost of being wrong: a wasted write path and a
   broken promise in the release notes.
2. **The cabin sensor is not a room thermometer.** It is the HVAC intake sensor near the dash. A
   car in the sun soaks: air at head height and the sensor can differ by several degrees, and the
   reading is only meaningful while the HVAC module is powered. Grade for the 7X specifically:
   **unknown**. Consequence: thresholds are comfort defaults, not safety guarantees, and the safety
   tier must sit far above them (35 / 40 °C) rather than at 30.
3. **An unattended seat heater is a fire-risk pattern.** Hyundai/Kia recalled cars for exactly this.
   The car exposes its own auto-off timers (`AUTO_SEAT_HEATING_TIME = 0x10050600`), so it probably
   protects itself — **unknown on the 7X**. Decision: seat heat only as part of an owner-issued
   `ac on` with a 10-minute cap, never as an automatic response to a cold-cabin alert. If the
   10-minute off write cannot be guaranteed (process killed), prefer not to offer seat heat at all.
4. **A sleeping car cannot be monitored.** Sentry off, or the bus asleep, means the 42 °C afternoon
   goes unreported. The alert can only promise "while the car is awake and sentry is on". Say it in
   the settings summary.
5. **The reply path has a gate and a window.** Telegram polling is gated on settings only, not on
   sentry mode: `syncPoller()` asks for alerts on, token and chat id filled, command replies
   allowed, and ntfy not the chosen transport — `boolean want = config.isAlertsEnabled() &&
   config.hasAlertsTelegramConfig() && config.isAlertsCommands() && !config.isAlertsNtfy()`
   (`SentryAlerts.java:600` in `syncPoller`, `:595`). The comment above it records why the old
   sentry requirement was wrong: after locking, the bus sleeps, the sentry reading comes back null,
   and the poller never started. The ntfy five-minute window still holds
   (`NTFY_COMMAND_WINDOW_MS = 5 * 60_000L`, `SentryAlerts.java:367`, window opened in
   `startNtfyCommands` at `:3039`), so on ntfy an `AC ON` sent 20 minutes after the alert is never
   read, while on Telegram it is read while the process is alive. Fix options: extend the ntfy
   window for climate alerts, or accept Telegram as the only transport where a delayed reply works.
   Neither is a foundation change; both are policy.
6. **Process death leaves the climate on.** The scheduled off dies with the process. The car's own
   remote-climate timeout may cover it (most do, ~30 min) — **unknown**. Keep the command window
   short and say the cap in the receipt.
7. **A climate power cycle has side effects:** recirculation resets to AUTO and the rear zone turns
   off (measured, AU 7X). Restoring them costs two more writes and two more unknowns. v1: do not
   restore, mention it in the settings note.
8. **Zone and channel uncertainty on the 7X.** Every float-channel fact comes from a different
   ECARX unit. Our own app has never written the setpoint. Expect one to three attempts to find the
   zone that the 7X accepts (`0x01`, `0x04`, `0x08`), and log each attempt's readback in the black
   box the way `CarMoves` already does.
9. **Quiet hours vs. heat.** A comfort alert at 02:00 is noise. The safety tier must bypass quiet
   hours, which means the existing `QuietHours` gate has to be told which tier is asking — a small,
   explicit parameter, not a silent exception.
10. **The car may already do this.** `HVAC_FUNC_OVERHEAT_PROTECTION` exists in the API and
    `SCENE_FUNC_PET_MODE_EXIT_WARN = 0x2f030500` sits in the same constant table. If the 7X has a
    factory overheat/pet mode, duplicating it is waste and a conflict. Probe before building the
    automatic path; if it exists, the alert's job is to tell you it happened.
11. **Data budget is not a problem.** Text only. That is the cheapest alert type in the feature,
    which is a good reason to build it: it demonstrates the pipeline without touching the 100 kB
    picture trimming work.
12. **Test surface grows.** New keys hit `PreferenceKeysTest`, `SettingsBackupTest` (new settings
    are exported by default), `StringResourceParityTest` (zh / en / ms), `HardcodedTextTest`,
    `DeveloperSettingsTest` if any row is developer-only, and `SettingsShotsTest` renders the
    screen — look at the PNG, not the XML.

---

## 8. Safety position

- Alerts are safe; writes are not. A text message can be ignored. A 3 kW heater cannot.
- **Never** start climate or a seat heater automatically because a temperature crossed a line. Only
  an owner command starts a write. (Automatic *off* is always allowed.)
- Never write while the car is moving or `gear != P`.
- Pet and child cases are alert-only, always. If a seat is occupied and the cabin passes 35 °C, the
  message must say so plainly and repeat, and no quiet hours may suppress it.
- The seat heat rule from §6 is not a preference to soften: outside ≥ 20 °C means no heat, full stop.

---

## 9. Test plan on the car, cheapest first

Ordered so each step can kill the next one before code is wasted.

1. **Log, do not write.** Parked, sentry on, black-box one line every 5 minutes with cabin/outside
   temperature, HVAC state, sentry, SoC. One night + one sunny afternoon. Answers: does
   `TEMP_INSIDE` keep moving while parked, how stale does it get, how far from reality is it at
   peak sun. **~1 hour of work, no risk.**
2. **The 20-minute write test (the one that decides the feature).** Parked, locked, sentry on,
   nobody inside. Read `HVAC_FUNC_POWER` for `0x08`; write `1`; read back for 30 s; check the
   factory climate screen and the fan signal. Then write `0`. Repeat with `setCustomizeFunctionValue(TEMP, 0x01, 23f)`.
   Answers: does a locked car accept it, which zone, does the setpoint stick.
3. **Seat heat while parked, HVAC off.** Confirms the smart-button write path works with the
   climate system asleep — it may not, because the seat module can be behind the HVAC module.
4. **Check whether the car already has overheat protection / pet mode** in the factory climate
   settings, and whether `0x10080400` answers at all.
5. **Probe list, grade `unknown` until measured:** `TEMP_UNIT` (is this a Fahrenheit market?),
   `AUTO_SEAT_HEATING_TIME` auto-off, whether the setpoint survives a power cycle, the car's own
   remote-climate timeout.

Until 2 passes, the release note can only promise the alert.

---

## 10. Settings: the collapse

Today: one `screen_alerts` section with **6 categories and 28 rows** (`preferences.xml:98` and the
`screen_alerts` block that follows it),
plus a channel row and 5 dialog rows. Both the left column and the page are long
(`ui-spec.md` §13 "设置的结构", which also caps the app at **three levels**).

The proposal in the brief — Alerts → Notifications / Sentry / Climate — is right about the cut.
Three ways to render it, with what each costs:

### A. One Alerts section + a 3-way segmented control (recommended)

Master switch on top, then a segment `Notifications | Sentry | Climate`, then only that family's
rows. Depth stays at **2** (left column → page), so the ui-spec §13 limit is untouched, the left
column stays at 10 rows, and the idiom already exists in this codebase (`SegmentedPreference`,
`SwitchChipsPreference`). The visibility work lands in one place, `showAlertsRows`
(`SettingsPreferenceFragment.java:1310`), which already hides categories per condition — this adds
one variable to the same switch.

Cost: `showAlertsRows` gets a family gate, and the segmented row needs a persisted key. Risk: the
page is still one fragment, so it stays a 3 600-line file.

### B. Alerts hub page → three child pages (`openFragment`)

Literally the proposed tree. The shell already supports pushing a child fragment
(`SettingsPreferenceFragment:3779` `openFragment`, already used for the profile editor), and the title bar updates on the
back stack. Depth becomes **3**, so those pages may open dialogs but not further pages.

Cost: three new fragments or one parameterised fragment with three XML roots; the shell's custom
nav button (`onNavClick`) and back handling need checking against the extra level.

### C. Three new left-column sections

`Alerts · Notifications`, `Alerts · Sentry`, `Alerts · Climate`. Zero navigation risk, depth stays
2, each gets its own §13 status line for free. Cost: the left column grows from 10 to 12–13 rows
and the master switch needs a home.

**Recommendation: A**, with B as the fallback if the segmented row reads badly on the car screen.
A solves the clutter without spending the one remaining level.

The branch shipped **A**: one segmented family picker (`pref_alerts_family`,
`SettingsRegistry.ALERTS_FAMILY`: notifications / sentry / climate) and every family's rows hidden
in one place, `showAlertsRows` (`SettingsPreferenceFragment.java:1411`).

Where the rows go under A:

| Segment | Rows |
|---|---|
| *(always)* | Alerts on · Where alerts go · live channel status |
| Notifications | token, chat id, find chat, Telegram/ntfy credentials, checks (probe, events, diagnostics), forgot-sentry ask mode |
| Sentry | triggers, arm delay, quiet hours + times, motion sensitivity, picture layout/cameras/straighten, clip length/audio/data |
| Climate | climate alerts on, too-hot limit, too-cold limit, safety limit, quiet-hours respect, default target temp, seat heat/ventilation + levels, SoC floor, 10-minute cap note, "needs sentry mode" note |

The climate page is **2 rows long when off** (switch + one honest line), which is the repo's own
rule: closed features take one row.

A/B/C are mocked side by side in `docs/climate-alerts/settings-shapes.html`.

---

## 11. What shipped

The slice table is gone; this is the code on the branch.

- Read and write the climate surface: `app/src/main/java/com/kooo/evcam/alarm/climate/ClimateSource.java`;
  float channel added in `telemetry/CarAccess.java` (`readCustom` / `writeCustom`) and
  `telemetry/EcarxSource.java`.
- Policy: `alarm/policy/ClimatePolicy.java`, `alarm/policy/ClimateSeats.java`.
- Command: `alarm/telegram/TelegramCommand.java` (`Verb.CLIMATE`, `parseClimate`, `ClimateAction`,
  `Climate`); ntfy reuses `parseText` as before.
- Glue: `alarm/SentryAlerts.java` — `climateCheck`, `sendClimateAlert`, `climateMarkup`,
  `onClimateCommand`, `startClimate`, `onClimateReport`, `scheduleClimateOff`.
- Settings: family picker `pref_alerts_family` in `res/xml/preferences.xml`, wiring and visibility in
  `settings/SettingsPreferenceFragment.java` (`showAlertsRows`), keys in `AppConfig.java` and
  `settings/SettingsRegistry.java`, strings in `values`, `values-en`, `values-ms`.
- Tests: `ClimateSourceTest`, `ClimatePolicyTest`, `ClimateSeatsTest`, `TelegramCommandTest`.

Not shipped, because it is not a code question: the on-car write test (§9 test 2). §9 and §13 keep
that gap open.

---

## 12. What I would not do

- No new wake path. Climate rides sentry's awake window; no alarm, no wakelock, no "wake the car at
  07:00" — that is a second 12 V drain this app did not measure.
- No automatic heating of anything, anywhere.
- No new transport, no server, no cloud call.
- No change to `Signal`, `Telemetry`, `policy/AlertPolicy`, `telegram/PollerGate`. If a slice needs one of those
  edited, that slice is wrong.
- No Fahrenheit UI in v1; read `TEMP_UNIT` and refuse if it is not Celsius rather than guessing.

---

## 13. Open questions, graded

| # | Question | Grade | How to settle |
|---|---|---|---|
| 1 | Does a locked, parked 7X accept HVAC power + setpoint from the local API? | **unknown** | §9 test 2 |
| 2 | Which zone does the 7X accept for `HVAC_FUNC_TEMP`? | **unknown** | §9 test 2 |
| 3 | Does `TEMP_INSIDE` keep moving while parked and sentry on? | **unknown** | §9 test 1 |
| 4 | Does the 7X have factory overheat protection / pet mode? | **unknown** | §9 test 4 |
| 5 | Is the setpoint float channel implemented on this adapter build? | **single-source** (other ECARX unit) | §9 test 2 |
| 6 | Temp range 16–31 °C, step 0.5 | **single-source** | read `0x10060{2,3,4}00` at runtime; never hardcode |
| 7 | Does the car drop HVAC by itself after N minutes? | **unknown** | leave it on for 30 min once, watch `HVAC_FUNC_POWER` |
| 8 | Does a seat-heat write work with climate off? | **unknown** | §9 test 3 |
| 9 | ntfy delayed reply window too short for climate | **confirmed** (code, `SentryAlerts:317`) | decide policy |
| 10 | Cabin-sensor bias at peak sun | **unknown** | §9 test 1 |
