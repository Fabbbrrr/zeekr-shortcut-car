# Climate alerts — decisions and ticket contracts

Branch `feat/climate-alerts`, based on `feat/parked-alerts-pr` @ `76b98bb4`.

The case, the evidence and the shortcomings live on `docs/climate-alerts` (`CASE.md`). This file is
the implementation contract: what each ticket owns, what it may not touch, and the exact signatures
the next ticket codes against. Read `CASE.md` first; do not re-litigate its decisions here.

## Scope of this branch

Ships:

1. **Detection** — cabin too hot / too cold / safety, text alerts only, on the existing transports.
2. **Command** — `ac`, `ac on`, `ac off`, `ac on 23`, `ac 23`; buttons on the alert itself.
3. **Writes** — power, setpoint (float channel), driver seat heat or ventilation; bounded by a
   10-minute auto-off, an SoC floor, and `gear != P` refusal. Every write is verified by readback and
   every failure is reported honestly to the chat.
4. **Settings** — the Alerts page is a hub with three child pages (`Notifications`, `Sentry`,
   `Climate`); the climate page holds its own switch, four sliders, the seat/level/minutes lists and the
   honest note.

Does not ship: rear or passenger seat writes, restoring recirculation/rear zone after a power cycle,
a new wake path, Fahrenheit UI, any automatic write that is not an owner command.

## Hard rules

- Never write a heater or the climate system automatically. Automatic **off** is allowed. Owner
  commands are the only start.
- Never write while moving (`Parked` false or `gear != P`).
- The temperature range and step are **read from the car** when the car answers (`HVAC_FUNC_TEMP_MIN/MAX/STEP`,
  float channel, integer channel as a fallback). 4.14.0 refused to write when the range was unreadable and
  that made `ac on` dead on the real 7X (its adapter answers neither channel), so 4.14.1 writes the owner's
  whole degree and lets the readback report what the car holds. No guessed 16–31 anywhere in Java: the
  plausibility window (10–35 whole degrees) is the parser's own limit, not a claim about the car.
- The setpoint goes through the **float** channel. The integer channel is refused by the vendor; do
  not add an integer fallback.
- Alerts are text only. No picture, no clip, no new transport.
- ASCII only in black-box detail strings and log lines. All user-visible text is a string resource in
  `values`, `values-en`, `values-ms`.
- `AlertPolicy`, `QuietHours`' rules, `PollerGate`, `Signal`, `Telemetry` are not edited. `CarAccess`
  gains two **default** methods (additive; existing fakes keep compiling).

## Settings keys (fixed here, so three tickets can agree)

| Key | Type | Default | Row |
|---|---|---|---|
| `alerts_climate_enabled` | bool | false | `pref_alerts_climate` (the page's own switch) |
| `alerts_climate_hot_c` | int °C | 30 | `pref_alerts_climate_hot` (slider 25–40) |
| `alerts_climate_cold_c` | int °C | 5 | `pref_alerts_climate_cold` (slider −10–15) |
| `alerts_climate_safety` | bool | true | `pref_alerts_climate_safety` |
| `alerts_climate_target_c` | int °C | 22 | `pref_alerts_climate_target` (slider 16–30) |
| `alerts_climate_seats` | string enum `off\|driver\|front` | `driver` | `pref_alerts_climate_seats` |
| `alerts_climate_seat_level` | int 1..3 | 2 | `pref_alerts_climate_seat_level` |
| `alerts_climate_minutes` | int | 10 | `pref_alerts_climate_minutes` (list 5/10/15) |
| `alerts_climate_soc_floor` | int % | 20 | `pref_alerts_climate_soc_floor` (slider 20–99) |

There is no `alerts_family` key and no family picker. The shipped shape is a hub
(`screen_alerts`, three entry rows: Notifications / Sentry / Climate — the checks live on the
notifications page, not on the hub) plus three child pages
under `screen_alerts_notifications`, `screen_alerts_sentry` and `screen_alerts_climate`, each one a
nested `PreferenceScreen` of the same `preferences.xml` loaded with `setPreferencesFromResource`'s
`rootKey`. The four numeric climate rows are `SeekBarPreference`s; the bounds and the getters'
clamping share the constants in `AppConfig` (`ALERTS_CLIMATE_*_MIN/MAX`).

## Contracts

### `telemetry/CarAccess` (additive)

```java
/** Float channel: setpoint and any other float-valued function. null / false when the unit lacks it. */
default Float readCustom(int id, int zone) { return null; }
default boolean writeCustom(int id, int zone, float value) { return false; }
```

### `alarm/climate/ClimateSource`

```java
public final class ClimateSource {
    public enum Outcome { DONE, NO_CHANGE, REFUSED, UNAVAILABLE, WRONG_UNIT }
    public interface Names { Integer function(String iface, String field); int zone(String name); }
    public interface Clock { void sleep(long ms); }

    public static final class Plan {
        public final boolean on;         // true: switch climate on; false: off
        public final float targetC;      // read only when on
        public final boolean seats;      // false: no seat writes at all
        public final boolean heat;       // write seat heating
        public final boolean vent;       // write seat ventilation
        public final int level;          // 1..3, used by heat or vent
    }

    public static final class Report {
        public final Outcome outcome;
        public final float targetC;      // what was actually written (after clamp/snap)
        public final boolean powerOn;    // read back
        public final boolean powerWrote; // the power write was accepted (the auto-off hangs on this)
        public final boolean seatWrote;  // the seat write, if any, was accepted
        public final String detail;      // ASCII, for the black box only
    }

    public static Report apply(CarAccess access, Names names, Clock clock, Plan plan);
}
```

`apply` order, all zoned reads/writes:
1. ids from `IHvac`: `HVAC_FUNC_POWER`, `HVAC_FUNC_TEMP`, `HVAC_FUNC_TEMP_MIN`,
   `HVAC_FUNC_TEMP_MAX`, `HVAC_FUNC_TEMP_STEP`, `HVAC_FUNC_TEMP_UNIT`, `TEMPERATURE_UNIT_C`,
   `HVAC_FUNC_SEAT_HEATING`, `HVAC_FUNC_SEAT_VENTILATION`. Missing `POWER` → `UNAVAILABLE`.
2. off: power 0 on the front zone, seats 0 when `seats`; read back power within 3 s → `DONE`, else `NO_CHANGE`.
3. on: read min/max/step (driver zone) best-effort; unreadable → write the requested whole degree
   (`atLeastPlausible`, 10–35) instead of refusing. Unit read, when
   non-null and not `TEMPERATURE_UNIT_C` → `WRONG_UNIT`. Target outside min..max → `REFUSED`.
   Snap to step.
4. write setpoint float on the **driver** zone; false → `REFUSED`.
5. write power 1 on the front zone; false → `REFUSED`.
6. seats (only when `seats`): heating level or ventilation level on the driver zone. A refused seat
   write does not fail the climate; it is recorded in `detail`.
7. read back power and setpoint for up to 3 s. `DONE` when power reads 1 and the setpoint is within
   half a step; power reading 1 with an unreadable setpoint is also `DONE` (this adapter answers nothing
   for some ids) with `power on, setpoint unreadable` in `detail`; a mismatched setpoint is `NO_CHANGE`.
8. the auto-off is scheduled when the power was read on **or** the power write was accepted
   (`Report.powerWrote`): ten minutes after locking the head unit is asleep, so the readback is empty and
   the accepted write is the only evidence. The task writes off only on a positive parked reading, retries
   every 30 s, and after 3 × minutes without one sends `alert_climate_off_unconfirmed`.

Front zone is `names.zone("ROW_1")` (0x08); the driver seat zone is `names.zone("ROW_1_LEFT")` (0x01).

### `alarm/policy/ClimatePolicy` and `alarm/policy/ClimateSeats`

```java
public final class ClimatePolicy {
    public enum Kind { NONE, HOT, COLD, SAFETY }
    public static final class Verdict { public final Kind kind; public final String reason; }

    public void reset();                       // new park session
    public Verdict decide(Float cabinC, Float outsideC, Integer soc, boolean parked, boolean hvacOn,
                          boolean seatOccupied, boolean paused, boolean quietNow, long nowMs);
}
```

Thresholds come from the constructor (`Limits` object or explicit floats): hotC, coldC, safetyC
(40), safetyOccupiedC (35), holdMs (10 min), repeatMs (15 min), staleMs (10 min), bandC (3), socFloor.
Rules:

- Not parked, or paused, or cabin null → `NONE`.
- `stale`: a gap of more than `staleMs` since the last non-null cabin sample resets the hold timer.
- hot/cold need the reading over the limit for `holdMs` of continuous fresh samples; then one alert
  per park session, re-armed only when the cabin crosses back past `bandC`.
- Safety needs no hold: `cabin >= safetyOccupiedC && seatOccupied`, or `cabin >= safetyC`. It ignores
  `quietNow` but not `paused`. Repeats every `repeatMs`, at most 3 per park session.
- `hvacOn` suppresses hot/cold (not safety). SoC below the floor suppresses hot/cold (not safety).

```java
public final class ClimateSeats {
    public static final class Choice { public final boolean heat; public final boolean vent; }
    public static Choice choose(float cabinC, float targetC, float outsideC,
                                float bandC, float heatOutsideMaxC, float ventOutsideMinC);
}
```

Heat only when `cabinC < targetC - bandC && outsideC < heatOutsideMaxC` (20). Vent only when
`cabinC > targetC + bandC && outsideC > ventOutsideMinC` (20). Never both, never otherwise.

### `alarm/telegram/TelegramCommand` (additive)

```java
public enum Verb { …, CLIMATE }
public enum ClimateAction { REPORT, ON, OFF }
public static final class Climate {
    public final ClimateAction action;
    public final int targetC;      // 0 = use the configured default
}
public final Climate climate;      // non-null only for Verb.CLIMATE
```

Accepted: `ac` / `climate` / `aircon` alone → REPORT; `ac on`, `ac off`; `ac on 23`, `ac 23`,
`ac 22.5` is **not** accepted (whole degrees only, 10..35; the parsed target stays an `int`). Numbers
outside 10..35, a decimal, or any extra word make the whole message `UNKNOWN`.
`toString()` prints `CLIMATE:ON@23` / `CLIMATE:ON` / `CLIMATE:OFF` / `CLIMATE:REPORT`.

### `alarm/telegram/TelegramPoller.Handler` (additive)

```java
void onClimate(TelegramCommand.Climate climate, long requestedAt);
```

`SentryAlerts` is the only implementation.

## File ownership per ticket

| Ticket | Owns (may edit) | Must not touch |
|---|---|---|
| T1 float channel | `telemetry/CarAccess.java`, `telemetry/EcarxSource.java`, `telemetry/EcarxSourceTest` if it exists | anything under `alarm/` |
| T2 source | `alarm/climate/**` (new), `app/src/test/java/com/kooo/evcam/alarm/climate/**` (new) | `CarAccess`, `EcarxSource`, `SentryAlerts` |
| T3 policy | `alarm/policy/ClimatePolicy.java`, `alarm/policy/ClimateSeats.java` (new), their tests | everything else |
| T4 command | `alarm/telegram/TelegramCommand.java`, `TelegramPoller.java` (handler + dispatch), `TelegramCommandTest`, `TelegramPollerTest` | `SentryAlerts` |
| T5 config/settings | `AppConfig.java`, `SettingsRegistry.java`, `res/xml/preferences.xml`, `SettingsPreferenceFragment.java`, `res/values*/strings.xml` | `alarm/**` |
| T6 glue | `SentryAlerts.java`, `AlertEvent.java`, `AlertButton.java` | the files above |
| T7 docs | `docs/climate-alerts/**` (copy of the case), `docs/sentry-alerts-spec.md` §1.9, `README.md`, `CHANGELOG.md` | code |

Integration order: T1 → T2/T3/T4 in parallel → T6 → T5 → T7.
