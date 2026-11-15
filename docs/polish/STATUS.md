# Loop state — 2026-11-15

Branch `feat/fork-polish` on the fork (`Fabbbrrr/zeekr-shortcut-car`), pushed at `df40481f`. It branches
off `feat/fork-build` (the branch `v4.5.2-beta` was cut from) and carries three `docs(ntfy)` commits from
a concurrent session — known, accepted.

- Gate at the pushed head: **1900 tests, 0 failures**, `assembleRelease` green.
- Findings: `findings/00`–`07`, review pass 1 in `review-pass1.md`, decisions in `DECISIONS.md`,
  what shipped in `CHANGELOG-polish.md`.

## Done

Findings implemented and gate-green: alarm 00 (all 10), camera 01 (all 5), zeekr 02 (01, 02),
settings/ui 03 (01–05), sound/profile 04 (01–07), root 06 (01–04, 06), recovery/overlay 07 (06–08),
playback 07 (01, 02, 07).

## Wave 2 — in flight when this was written

| Worker | Findings | Files |
|---|---|---|
| playback/share | F-07.03, F-07.04, F-07.05 | `share/PhoneShare.java`, `playback/PhotoPlaybackActivity.java`, `playback/ExpandablePhotoGroupAdapter.java` |
| button/CI | F-06.05, F-06.09 | `button/MediaMove.java`, `.github/scripts/release_notes.py` |

## Deliberately not changed (documented in `DECISIONS.md`)

D-04 Signal 255 sentinel · D-05 `segmentMs(0)` comment only · D-11 settings dialogs lost across a
recreate (needs device work) · F-02.03 dewarp wait (no field log) · F-06.07 StaleGatt reflection (needs a
device) · F-06.08 manifest media permissions (upstream's, harmless) · F-06.10 BootReceiver `goAsync`
(that broadcast has never arrived on this head unit).

## Next

Gate after wave 2, commit per area, push. Then: no new code without a finding behind it — the remaining
pool is device-only.
