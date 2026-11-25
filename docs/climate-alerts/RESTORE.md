# Climate restore — the decision, the contract, the cost

Branch `feat/climate-restore`, based on fork `main` @ `5101fd66` (4.19.2-alerts-beta).
Read `CASE.md` and `DECISIONS.md` first; this file adds one decision and does not re-litigate
the others. Written 2026-11-26 after the owner's report from the car.

Grades follow AGENTS.md: **confirmed** (two sources), **single-source**, **measured**, **unknown**.

## 1. The report

The owner sets the climate from the chat (`ac 23`), the ten-minute auto-off fires, and when they get
back in the car the settings are not the ones they drove with. That is the shipped behaviour, not a
regression: `DECISIONS.md` says *"Does not ship: … restoring recirculation/rear zone after a power
cycle"*, and `CASE.md` §7 item 7 chose to mention it rather than restore it. Nothing in the code
remembers anything, so nothing can come back.

Three facts make it hurt on the car:

1. Every write is destructive and blind — the setpoint and seat levels are written straight from the
   command and from settings (`ClimateSource.on`), with no read of the prior state.
2. A climate power cycle resets things on this car family: **circulation goes back to AUTO and the
   rear zone stays off** (`car/docs/australian-7x-recirculation-findings.md` §1, §3 — on-car
   observation, measured). Whether the *setpoint* and seat levels survive a cycle is still
   **unknown** (`CASE.md` §13 question 5) — and those are exactly the fields this app writes.
3. The settings behind the buttons come from the settings screen (target 22, level from
   `alerts_climate_seat_level`), so the car's own memory now holds the app's value, not the owner's.

## 2. Decision

**Auto-off restores what it overwrote.** Before the first write of an owner climate command the app
takes a snapshot of the fields it is about to change; when the auto-off fires, it switches the climate
off and replays that snapshot. The car is exactly as the owner left it, settings included.

- The snapshot is **read-before-write, field by field**. A field that cannot be read is `unknown`,
  `unknown` is a legal grade, and an unknown field is **not written** at restore time. No invented
  defaults (the no-guess rule, enforced in code).
- The restore writes only: setpoint (both front zones, same units path as the write), front seat
  heat/vent per side, circulation, rear power. It does **not** switch the climate on — "never start
  climate automatically" still holds; the owner's own ON from the head unit now finds its own settings.
- A field that reads back equal to the snapshot is skipped (no pointless write) — this mirrors the
  AU-7X prototype's rule (*"Already-correct targets were skipped"*, recirculation findings §4).
- The chat receipt's promise stays as it is; per-field restore results go to the black box. A restore
  write that the car refused is said out loud with the existing no-change sentence.
- A manual `ac off` is still a plain off (owner asked for off, settings included). The restore is for
  the auto-off the receipt promised.
- `alerts_climate_restore` (bool, default **on**) is the escape hatch: off keeps today's behaviour
  exactly, for a car where the extra writes misbehave.

**Cost, written down:** one snapshot read pass plus one write per remembered field, on an adapter that
answers nothing to many reads (a `null` read and a `false` write mean "this head unit cannot", not
"something broke" — `ClimateSource` class doc). The snapshot lives in process memory only: if the
process dies mid-window the scheduler dies with it, and the next auto-off is a plain off — which is
the honest shape, because nothing was overwritten in that new process.

**Against the design (adversarial, written before building):** the extra writes happen at the moment
the head unit is most likely asleep — the same reason `ClimateOffGuard` exists. So the restore must
tolerate refusal without retry storms (one attempt per field, no dwell loop, no readback requirement),
must not claim success it cannot see, and must never block the off itself: **off first, then restore.**
If the reviewer finds a vacuous test here, that is the place to look.

## 3. Contract (signatures are fixed; slices code against this file)

### `alarm/climate/ClimateSnapshot` (new, pure Java like `ClimateSource`)

```java
public final class ClimateSnapshot {
    /** Read-only pass: reads each field through CarAccess + Names, never writes. */
    public static Snapshot read(CarAccess access, Names names);

    /** True when at least one field is known — a fully unknown snapshot restores nothing. */
    public static boolean anyKnown(Snapshot s);

    /** Immutable value object. null = unknown on this head unit (legal, and not written back). */
    public static final class Snapshot {
        public final Integer frontPowerC;   // HVAC_FUNC_POWER @ ROW_1 (may legitimately be 0 or null)
        public final Float driverSetpointC; // float channel @ ROW_1_LEFT
        public final Float passengerSetpointC; // float channel @ ROW_1_RIGHT
        public final Integer seatHeatLeft;  // raw stored value (function id + level, or 0)
        public final Integer seatVentLeft;
        public final Integer seatHeatRight;
        public final Integer seatVentRight;
        public final Integer circulation;   // raw value (id + 1 inner / + 2 outside / + 3 auto)
        public final Integer rearPower;     // HVAC_FUNC_POWER @ ROW_2
        public int knownCount();            // fields that are not null
    }
}
```

Placeholder readings `253` / `255` (the adapter's "no value" answers, recirculation findings §2) are
recorded in `detail` and stored as **unknown**, never restored. The seat/circulation reads are known
to be unreliable on this adapter — unreliable means "often null", not "lie"; a value that *is* read is
trusted, the same rule `ClimateSource.seatsOnly` already applies in reverse.

### `alarm/climate/ClimateSource` (extended, same file)

```java
/**
 * Replay a snapshot after the off. Reads back first and skips what already matches (an unchanged
 * setting is not touched); writes what drifted, one attempt per field, no dwell loop, no failure
 * storm. Acceptance is what is reported; results go to detail (ASCII, black box).
 */
public static Report applyRestore(CarAccess access, Names names, Clock clock, Snapshot snapshot);
```

Writes: setpoint through `writeFloat` on both front zones (unit conversion through the existing
`speaksFahrenheit` path), seat heat/vent with the raw stored value (id + level, 0 = off), circulation
with its raw value on the default scope, rear power on `ROW_2`. Null field → no write, noted. Only
fields that exist in `Names` are attempted.

### `alarm/SentryAlerts` (glue)

- Capture happens **on the `alert-climate` thread**, at the top of the same `climateWork` runnable that
  performs the write, only when the command is an ON and `config.isAlertsClimateRestore()`; stored in a
  volatile field `climateSnapshot`. The main thread does no binder reads.
- `attemptClimateOff` → after the existing off plan (which keeps its current behaviour exactly), if
  the off reached the car and a snapshot with `knownCount() > 0` exists and the setting is on → call
  `applyRestore` on the same thread, generation-guarded like the off itself (a dropped chain writes
  nothing).
- The generation guard, the parked gate and the RETRY/GIVE_UP shape of `ClimateOffGuard` are unchanged.
- With the setting off, the code path is byte-identical to today's (baseline invariant).

### Settings (T5 shape)

- Key `alerts_climate_restore`, bool, default true; `AppConfig.isAlertsClimateRestore()` /
  `setAlertsClimateRestore(boolean)`; row `pref_alerts_climate_restore` (SwitchPreference) inside the
  existing Climate segment in `res/xml/preferences.xml`, right after the seat level row; strings in
  `values`, `values-en`, `values-ms` (parity test will fail otherwise).

## 4. File ownership (one writer per file)

| Slice | Owns | Must not touch |
|---|---|---|
| W1 core | `alarm/climate/ClimateSnapshot.java` (new), `alarm/climate/ClimateSource.java` | everything else |
| W2 app glue | `alarm/SentryAlerts.java`, `AppConfig.java`, `settings/SettingsRegistry.java`, `res/xml/preferences.xml`, `res/values/strings.xml`, `res/values-en/strings.xml`, `res/values-ms/strings.xml` | `alarm/climate/**`, tests |
| W3 tests+docs | `app/src/test/java/com/kooo/evcam/alarm/climate/ClimateSnapshotTest.java` (new), `ClimateRestoreTest.java` (new), `docs/climate-alerts/RESTORE.md` (append the execution log; contract text frozen), `CHANGELOG.md` | everything else (tests it cannot compile-run — parent runs the suite) |

Slices branch off this contract commit in their own worktrees. `SentryAlerts.java`, `ClimateSource.java`
and `strings.xml` are hot files — exactly one owner each, above.

## 5. Gates

- `ClimateSnapshotTest`: unknown (null) fields produce **no write** and are named in detail; the 253/255
  placeholders are stored as unknown; a read value is kept verbatim (no unit conversion at snapshot
  time — the snapshot stores what the car holds on that channel).
- `ClimateRestoreTest`: writes exactly the known fields with the right id/zone/value pairs; a field
  already equal at readback is skipped; unit conversion matches the write path's (a Fahrenheit-speaking
  car gets the converted number, `speaksFahrenheit` rules); a refused write does not stop the pass and
  is noted; null snapshot writes nothing.
- `SentryAlerts` wiring tests (extend `AlertClimateAutoOffTest`-style harness, package
  `com.kooo.evcam.alarm`): with restore on and a known snapshot, an auto-off that reaches the car also
  runs the restore; with the setting off it does not; a dropped generation runs neither; a snapshot that
  is entirely unknown runs the off only. The existing auto-off tests must pass unchanged.
- Settings wiring (extend `ClimateSettingsWiringTest` shape): the row exists in the Climate segment,
  toggling stores the value and triggers `SentryAlerts.refresh()`.
- `StringResourceParityTest`, `HardcodedTextTest`, `PreferenceKeysTest`, `SettingsBackupTest` green —
  new keys are exported by default and every new string exists in `values`, `values-en`, `values-ms`.
- Full `:app:testDebugUnitTest` on the merged tree, baseline = whatever fork `main` @ `5101fd66`
  reports + the new tests only. Robolectric tests need `@Config(sdk = 34)` (CI runs JDK 17).

## Execution log

Slice W3 (branch `slice/restore-tests`). Tests written against the §3 contract signatures exactly;
none of them compiles on this branch (the classes under test land with W1) — **nothing here was run**.
All entries: `written, run pending merge`. Assertion style: the fakes' recorded calls (id/zone/value),
per the house rule.

### ClimateSnapshotTest (`app/src/test/java/com/kooo/evcam/alarm/climate/ClimateSnapshotTest.java`)

- `theReadPassNeverWrites` — the snapshot pass reads all nine fields and issues zero writes. written, run pending merge
- `anUnknownFieldProducesNoWrite` — the null field gets no write back after drift; other known drifted fields are written (anti-vacuous). written, run pending merge
- `anUnknownFieldIsNamedInDetailWhenRestored` — the unknown field is named in detail (assertion pinned to the lowercase word `circulation`; wording itself is not contract-frozen — see ambiguities) and detail stays ASCII. written, run pending merge
- `aPlaceholderReadingIsStoredAsUnknown` — 253/255 readings store as null; channels change to real values after the snapshot; the pass still writes nothing. written, run pending merge
- `theOtherFieldsAreKeptWhenOneReadIsAPlaceholder` — one placeholder does not poison the other fields; the kept zero writes back, the unknown does not. written, run pending merge
- `aLegitimateZeroIsAKnownValue` — a real 0 (off / level 0) is a value: stored, and written back when the car moved off it; front power is never written. written, run pending merge
- `aReadValueIsKeptVerbatimOnAFahrenheitCar` — a Fahrenheit car's channel numbers (68.0 / 73.4) store verbatim — no unit cut at snapshot time; nothing drifted so nothing is written. written, run pending merge
- `knownCountCountsTheFieldsThatAreNotNull` — 7 readable fields, 2 nulled → knownCount 7. written, run pending merge
- `anyKnownIsFalseForAFullyUnknownSnapshot` — all-null snapshot → false. written, run pending merge
- `anyKnownIsTrueWhenAtLeastOneFieldIsKnown` — one readable field → true; the read pass still wrote nothing. written, run pending merge
- `everyFieldIsResolvedThroughNamesAndZones` — every id/zone/constant (including `IHvac.HVAC_FUNC_CIRCULATION` and `ROW_2`) is really asked of Names; no writes. written, run pending merge

### ClimateRestoreTest (`app/src/test/java/com/kooo/evcam/alarm/climate/ClimateRestoreTest.java`)

- `everyDriftedKnownFieldGetsItsSnapshotValueWrittenBack` — the eight drifted known fields get exactly their id/zone/value pairs (both front zones get the setpoint). written, run pending merge
- `eachSeatKeepsItsOwnLevelOnItsOwnZone` — driver heat keeps the driver's level on the driver's zone, passenger its own on its own (crossed wires go red). written, run pending merge
- `aFieldAlreadyEqualAtReadbackIsSkipped` — the one field the car still holds equal to the snapshot is untouched; the drifted ones are written. written, run pending merge
- `theFrontPowerIsCapturedButNeverWrittenBack` — front power is remembered (snapshot holds 1) but no write touches it — "never start climate automatically" holds. written, run pending merge
- `aFullyUnknownSnapshotWritesNothing` — empty adapter → all-null snapshot, `anyKnown` false, zero writes, no crash. written, run pending merge
- `aNullSnapshotWritesNothing` — a null snapshot argument: zero writes, no crash. written, run pending merge
- `anUnknownFieldIsNotWrittenBackEvenWhenTheCarStartsAnswering` — unknown at snapshot stays unwritten even if the id answers again by auto-off time. written, run pending merge
- `aFahrenheitCarGetsBackTheNumberItsChannelHolds` — the Fahrenheit car's channels get their own 68.0 back on both front zones; the double-conversion numbers (154.4/154.3) and the read-side-converted 20.0 must not appear. written, run pending merge
- `theUnitRuleComesFromTheSetpointNotTheUnitId` — the 4.15.0 car (unit id says F, setpoint 19.5): gets 19.5 back, not 67.1/67.2; the pure rule mirrors (`speaksFahrenheit`) agree with the write path's existing pins. written, run pending merge
- `aRefusedWriteDoesNotStopThePassAndIsNoted` — the refused field is attempted once, every other drifted field is still attempted, and the refusal is named in detail (lowercase `refused`; wording not contract-frozen — see ambiguities); detail stays ASCII. written, run pending merge
- `everyDriftedFieldGetsExactlyOneAttemptAndThePassDoesNotDwell` — exactly one write call per drifted field, and the fake clock is never woken (contract: one attempt per field, no dwell loop). written, run pending merge
- `aFieldMissingFromNamesIsNotAttempted` — the constant missing from Names means its fields read unknown and never get a write; the rest is written. written, run pending merge

### Ambiguities (pinned to the reading the contract's own words support)

- **"unit conversion matches the write path's"** vs **"no unit conversion at snapshot time — the
  snapshot stores what the car holds on that channel"**: since the snapshot stores the channel verbatim,
  a round-trip through a Fahrenheit car lands the same number back on the channel (68.0). The Fahrenheit
  tests pin the number that reaches the channel plus negatives (no double conversion, no single-sided
  conversion), which every reading of the pair must satisfy; they do not pin *where* the cut happens.
- **Detail-wording asserts**: the contract fixes only that results go to detail (ASCII). The two wording
  asserts pin the lowercase field word (`circulation`) and the word `refused` — the narrowest honest
  reading of "named in detail" / "is noted". If W1's wording differs, adjust the assert strings only.
- **`knownCount()` on the all-known fake = 9** assumes all nine contract fields are read via
  CarAccess+Names on a fully-answering adapter. If W1 legitimately cannot resolve one id on the fake,
  the fake's `FakeNames` gains it (test-side file, W3 owns it) — not the field list.
