# Baseline — measured at branch start

Branch `feat/fork-polish` from `feat/fork-build` @ `c1d7fba1` (the branch `v4.5.2-beta` was cut from).
Measured on lai, 2026-11-15.

| Gate | Result | How measured |
|---|---|---|
| `:app:assembleRelease --offline` | BUILD SUCCESSFUL, 3m31s | gradle exit 0, `app-release.apk` 22,913,958 bytes |
| `:app:testDebugUnitTest --offline` | 168 classes, **1873 tests, 0 failures, 0 errors, 0 skipped** | `app/build/test-results/testDebugUnitTest/*.xml` |
| Toolchain | Gradle 9.1.0, AGP 9.0.0, compileSdk 36, JDK 21, `sdk.dir=C:/Users/lai/android-sdk` | `local.properties`, successful build |
| Tree | 679 tracked files, 490 `.java`, 171 test files | `git ls-files` |

Nothing was broken at the start. Every finding after this point is a change in behaviour, so every fix
gets its own commit and both gates re-run before it is kept.

## Packages audited (one auditor each, disjoint file lists)

| Slice | Packages | Findings file |
|---|---|---|
| 00 | `alarm/` (our feature) | `findings/00-alarm.md` |
| 01 | `camera/` | `findings/01-camera.md` |
| 02 | `zeekr/` | `findings/02-zeekr.md` |
| 03 | `settings/`, `ui/` | `findings/03-settings-ui.md` |
| 04 | `sound/`, `profile/`, `telemetry/` | `findings/04-sound-profile-telemetry.md` |
| 06 | root, `button/`, `update/`, `blackbox/`, `storage/`, `recording/`, `service/`, manifest, CI | `findings/06-root-button-update-misc.md` |
| 07 | `playback/`, `overlay/`, `screen/`, `setup/`, `recovery/`, `share/` | `findings/07-playback-overlay-share.md` |

Upstream activity that would overlap is in `UPSTREAM-QUEUE.md` — do not re-implement what an open
upstream PR already claims.
