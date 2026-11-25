# Adversarial review — climate restore (wt-restore-c @ d05b057b)

Read-only pass; this file is the review artifact, not part of the frozen contract.

## Findings

### Major

- **major** — Contract promises a refused restore write "is said out loud with the existing no-change sentence" (docs/climate-alerts/RESTORE.md:43), but the glue speaks only on `REFUSED` (SentryAlerts.java:4084) while W1 maps a refused write to `NO_CHANGE` (ClimateSource.java:822) — a car that refuses a restore write is never told, promise broken.
- **major** — The contract's own resolution of the Fahrenheit pair is a real bug on a genuine Fahrenheit car: verbatim channel value 68 replayed when the current readback still reads >45 gets converted again and writes 154.4°F (ClimateSource.java:792 with ClimateSource.java:702); W1 documented the collision (ClimateSource.java:300 region), nobody resolved it — the write lands a setpoint no unit accepts.
- **major** — Every `SentryAlerts` wiring test demanded by RESTORE.md §5 (restore runs after an off that reached the car; setting off does not; dropped generation runs neither; fully-unknown snapshot runs off only) and the settings-wiring toggle test are absent — the entire glue (SentryAlerts.java:3720-3722, 4060-4088) has zero test coverage; only W3's pure-JVM files exist.

### Minor

- **minor** — `aFahrenheitCarGetsBackTheNumberItsChannelHolds` is self-dodging: its drift puts the readback at 25 (Celsius band, ClimateRestoreTest.java:376), so the unit rule says "no conversion" and the 154.4/154.3 negatives (ClimateRestoreTest.java:245-246) are unreachable — the assertion that pins the exact case where the bug fires can never fire.
- **minor** — Restore runs only when the off outcome is `DONE` (SentryAlerts.java:4061) — on an accepted-but-unconfirmed off (`NO_CHANGE`, the normal case when the head unit sleeps, which is why `powerWrote` and ClimateOffGuard exist) the settings were overwritten and the restore silently skips; defensible under "if the off reached the car", but the decision sentence "switches the climate off and replays that snapshot" (RESTORE.md:36-44) states no such gate.
- **minor** — Contract says the settings toggle "triggers `SentryAlerts.refresh()`" (RESTORE.md:154); the row goes through `bindSwitch`, which only calls `requestSync()` (SettingsPreferenceFragment.java:4603-4617) — behaviour is right by accident because the setting is read live at capture/restore time, but the pinned wiring differs from what was written and nothing tests it.
- **minor** — W2's never-clear attack: the drive-away/drive-back/no-ON case is safe (the chain is cancelled with the session, and every ON recaptures), but one hole stands — an ON run while the setting is off captures nothing, and if the setting is switched on before the timer, restore replays the previous session's stale snapshot (capture gate SentryAlerts.java:3720 vs live gate SentryAlerts.java:4061).
- **minor** — Two ON commands in a row: the second snapshot records what the first command wrote from settings, so the auto-off restores the app's value, not "exactly as the owner left it" (RESTORE.md:36-44); same shape for a manual seat-button change between the ON and the auto-off — it is silently reverted by the replay.
- **minor** — SettingsShotsTest comment now lies: "八行（开关自己不在里面）" (SettingsShotsTest.java:1365) above a nine-row list that contains a switch row; the test's assertions are fine, the comment is stale.

### Note

- **note** — The restore's binder traffic runs on the climate thread before the off's chat receipt is posted (SentryAlerts.java:4010), so "never blocked by the restore" holds for the off write but the off's user-facing receipt is delayed behind the restore pass.
- **note** — Contract's `Snapshot` sketch has no `detail` field (RESTORE.md §3); the implementation adds `public final String detail` (ClimateSnapshot.java, field block) and the tests depend on it — signature drift in the direction the prose implied, but the sketch said signatures are fixed.
- **note** — Settings-summary wording verified against code: "puts back the settings it just changed; what it cannot read it leaves alone" matches read-then-write-only-drifted behaviour; only the "said out loud" leg is broken (first major).
- **note** — CHANGELOG entry matches shipped behaviour except the loud-refusal promise inherited from the contract; the "first measurement" honesty line is accurate.
- **note** — Execution log vs files: all 11 ClimateSnapshotTest and 12 ClimateRestoreTest entries exist and match their described assertions (fakes' recorded calls, ASCII checks, verbatim/placeholder/zero cases all as logged); the only log-true-but-test-weak case is the Fahrenheit one above.
- **note** — Generation guard present at every step the contract names (drop check before applyRestore, SentryAlerts.java:4064-4067; dropped chain notes and writes nothing); null-argument snapshot handled (ClimateSource.java applyRestore guard); process-death story is genuinely honest since the scheduler and the volatile field die together.
- **note** — Existing auto-off tests: untouched and expected to pass — the harness reads answer nothing, so the default-on capture pass stores all-unknown and the DONE gate keeps restore out of those scenarios; but with the default ON, every real ON command now pays the nine-read pass before the write (documented cost, unmeasured).

## Verdict

Yes — the refused-write speaks-leg must be fixed or descoped in writing before the release tag, and the missing SentryAlerts wiring tests are the gate the plan itself called the place to look; the Fahrenheit double-conversion needs a written decision (code matches the contract; the contract is wrong) even if the code stays as-is.
