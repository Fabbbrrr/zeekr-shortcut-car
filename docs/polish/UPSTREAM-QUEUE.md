# Upstream queue — open PRs and issues on `dts88/zeekr-shortcut-car`

Read 2026-11-15 via `gh` (body, comments, changed-file list). Mergeable state from the GitHub API.
Our branch is `feat/fork-polish`, cut from `feat/fork-build` (`c1d7fba1`); our own open upstream PR is #211.

Overlap risk is against our repo's packages as they exist on this branch, not against other upstream PRs.

## Open PRs

| # | What it actually does | Files / packages touched | Mergeable | Overlap with our work | Ours too? |
|---|---|---|---|---|---|
| 113 | Drive sound: per-second sound log in diagnostics (last 90 s: level written, gain, volume, audio mode, who plays/records) — measurement only, no behaviour change | `sound/DriveSound.java` (+163) | mergeable | HIGH — `sound` + `zeekr` diagnostics; slice 00 already reads `EglSurfaceEncoder`, `blackbox` | yes — the instrument the drive-sound ducking report needs, costs nothing |
| 60 | Tag builds re-sign with the owner's private key (v3 lineage from the test key); CI signs and checks; key lives in the `release` environment | `.github/workflows/build.yml`, `.github/scripts/release_key.py`, `sign_release.sh`, `app/build.gradle.kts`, `zeekr/DiagnosticsCollector.java` | conflicting | HIGH — `.github` CI + `zeekr/DiagnosticsCollector.java`; we already audit `alarm` through this branch | no — the owner's key and the upstream release environment; our fork keeps its own keystore |
| 54 | Independent visible preview selection, decoupled from what records (7 modes, incl. Surround + Front + Rear) | 42 files: `camera` (ChannelRules, ChannelScheduler, MultiCameraManager, CameraManagerHolder), `profile` (SourceMappings, RecordingSources, ProfileChannels), `settings`, `MainActivity`, `zeekr/ZeekrMultiPlan`, res/layout, tests, `docs/independent-preview-layout.md` | conflicting (depends on #52) | MED — `camera`, `profile`, `settings`; not `alarm` | no — pulls in #52 first; wait for upstream to resolve the chain |
| 53 | Combine recording source groups (Surround / Front+Rear / Cabin / Manual) with separate road clips and road-only playback | 44 files: `camera`, `profile` (RecordingSources, SourceMappings), `playback`, `settings`, `ui`, `zeekr` (RecordingCatalog, TimelinePlayerActivity), res/layout, tests | conflicting (depends on #52) | MED — `profile`, `camera`, `playback` | no — same dependency chain as #52 |
| 52 | New source roles (exterior-front, exterior-rear, manual) + one shared camera-mapping picker; foundation for #53/#54 | 29 files: `camera` (CameraAvailabilityWatch, CameraContention, CameraManagerHolder, CameraNames, MultiCameraManager), `profile` (SourceMappings, RecordingSources, ProfileStore), `settings/CameraSourcesFragment`, res, `docs/shared-camera-sources.md` | conflicting | MED — `camera`, `profile`, `settings` | no — claimed by wysie; cherry-pick only if we need the roles |
| 51 | One confirmed Stable/Beta/Alpha channel choice replacing the Include beta / Include alpha switches | 17 files: `update` (GithubReleases, UpdateChannel, UpdateFlow, VersionName), `settings`, `AppConfig`, res/xml + strings, tests | conflicting | LOW — `update` untouched by our slices | no — already upstream-supported; cosmetic consolidation |
| 50 | Center-screen blind-camera popup: square/portrait/landscape/ultra-wide shapes, adjustable height, drag/pinch placement | 14 files: `zeekr` (SideViewPlacement, SideViewService, SideViewWindow), `settings`, `AppConfig`, res, tests | conflicting | LOW — `zeekr` side-view only | no — nice-to-have, off our scope |
| 47 | Optional "keep automatic side-camera fitting off": preserve requested FOV/aim, fill gaps with opaque black | 13 files: `zeekr` (FisheyeMesh, SideViewFraming, SideViewPicture), `settings`, `AppConfig`, res, tests | conflicting | LOW — `zeekr` side-view only | no — rendering-polish path we are not touching |
| 44 | Ignore capture results from a replaced session before they touch liveness/FPS — stops a stalled replacement looking alive | `camera/SingleCamera.java` (+3/−3), androidTest | conflicting | MED — `camera` liveness, adjacent to our `alarm` motion taps | yes — three lines, fixes a real reporting lie, cheap to take |
| 37 | Yield automatic UI recovery to reverse, factory surround, factory popup and parking assist | `recovery/Recovery.java`, new `recovery/RecoveryUiPolicy.java` + test | conflicting | MED — `recovery`, and `screen`/`overlay` neighbours | yes — our alerts already fight foreground grabs; the guard matches our gate |
| 32 | Developer options behind a persistent System switch with confirmation; password entry on About removed | 18 files: `settings` (DeveloperMode, SettingsShellFragment, SettingsHeadersFragment), `zeekr/AboutActivity`, res strings, tests | conflicting | LOW — `settings` only | no — settings UX the owner already owns |
| 29 | Settings reflow: camera switches stay reachable with long names/badges; cards, segmented rows and the action rail reflow in narrow windows | 13 files: `settings` (SegmentedPreference, ProfileEditorFragment, SettingsShellFragment), `ui` (ResponsiveCardRow, SegmentedBar), res/layout, androidTest | conflicting | LOW — `settings`/`ui` layout only | no — cosmetic, wide-layout behaviour unchanged |
| 211 | Ours: Sentry alerts push one surround picture (optional 5/10 s clip) to Telegram when the car is disturbed; query on demand | 61 files: `alarm` (19 new), `camera` (CameraKeeper, CodecVideoRecorder, EglSurfaceEncoder, MultiCameraManager), `settings`, `ZeekrShortcutApp`, `AppConfig`, res, tests, `docs/sentry-alerts-spec.md` | mergeable | HIGH — ours; `alarm` slice 00 | — (this is ours) |

## Open issues

| # | What it actually asks | Files / packages it would touch | Overlap risk | Ours too? |
|---|---|---|---|---|
| 11 | "Send to phone" times out to Android phones; iOS works. Owner confirms the bug, no fix yet | `share/PhoneShare.java`, `share/FileShareServer.java`, `share/LocalNetwork.java`, `share/QrCode.java` | LOW — `share` unclaimed | yes — several owners hit it, nobody has claimed it |
| 26 | Signal points for horn, emergency braking, multi-axis acceleration (hazard + high beam done). Horn: no usable signal; acceleration: not done | `telemetry` (EcarxSource, Readings, FastReadings), `alarm/TriggerSet`, `storage/AutoLock` | MED — touches our `alarm` triggers | no — the owner tracks it; horn is a dead end, acceleration needs a car |
| 57 | Wi-Fi auto-upload of footage to NAS / Drive / OneDrive, module kept decoupled, plus wake-lock research | new package (upload) + `storage`, `WakeUpHelper`, `recording/ScreenOffRecording` | MED — `storage` + wake path | no — big new subsystem, needs a decision doc first |
| 68 | Proposal: drop the main-screen live preview; use Super mirror instead; decide what the main screen shows instead | `MainActivity`, `ui` (PreviewStageLayout, PreviewExpandAnimator), `camera` scheduler + PreviewSlots, `settings` | MED — main screen and camera scheduler | no — open design call the owner has not settled |
| 98 | Add a farting sound inside and outside | `sound` (new voice), `settings` | LOW — `sound`, adjacent to #113 | no — joke ticket, unclaimed |
| 99 | New sideload method after App Lab changed; workaround posted (open a link from a YouTube clip); request to put an install-browser link in settings | docs / `settings` + About only | LOW | no — docs note, not code |
| 137 | Smart-button control for hazard lights, plus renaming/aliases for smart buttons | `button` (SmartButtons, SmartButtonAction), `telemetry` write path | LOW — `button` unclaimed | yes (rename only) — the hazard command is accepted but the lights do not react on the car |
| 138 | Restore preferred settings at drive start: recirculation, auto high-beam, second-row AC (optional, applied once per drive). Author has recirculation and rear AC working on their car | new package (settings-restore) + `telemetry` write path, `settings` | MED — `telemetry` writes | no — author owns it and has a test APK; upstream wants their code |
| 155 | Trunk opening height per location (a different limit away from the low-roof garage) | `telemetry` + new location rule; no code touched yet | LOW | no — needs geofencing, out of scope |
| 169 | How to install the unofficial app on a Zeekr 7x (support question, no code) | none | none | no — FAQ/docs only |
| 195 | Wider front/rear wide views on button press; owner says factory reverse view outranks any app, Super Mirror is the answer | `zeekr` side-view / `settings` | LOW | no — answered; parked on Super Mirror |
| 198 | Tuning pass on engine-voice synthesis: deeper V8 fundamental, aggressive lift-off/crackle on decel | `sound` (EngineVoice, Voice, VirtualEngine, Biquad, VoiceParams) | HIGH — `sound`, collides with #113 | no — the owner's sound work; would conflict with #113 in the same files |
| 200 | Repo polish: screenshots in changelog/README, a demo video, enable GitHub Discussions | `docs/`, `docs/screenshots`, README/CHANGELOG | LOW | no — the owner's repo, not our fork's files |
| 203 | Closed (completed): how the ECARX function IDs were found; window-switch polarity idea | `telemetry/EcarxSource` | LOW | no — closed |
| 209 | After reinstall via the new exploit, app opens straight to "camera and storage permissions are required" with no permission dialog and no way to grant | `MainActivity` permission request path, `settings/PermissionsPreferenceFragment`, `AndroidManifest` | MED — `MainActivity` shared with #54/#53 | yes — no dialog means no recovery for the owner; the fork's permission path is a plausible fix |

## Do not duplicate

Numbers we should not re-implement on the fork — each is someone's live claim or the owner's own.

| # | Reason |
|---|---|
| 52, 53, 54 | wysie chain, depends on #52 |
| 51 | upstream already supports |
| 50, 47 | wysie side-view claims |
| 32, 29 | wysie settings/layout claims |
| 113 | owner's own diagnostics PR |
| 60 | owner's key infrastructure |
| 26 | owner tracks signals |
| 98 | joke ticket, no owner |
| 99 | answered, no code |
| 137 | hazard blocked on car |
| 138 | author owns test APK |
| 155 | geofencing, out of scope |
| 195 | answered, parked |
| 198 | owner's sound work |
| 200 | repo housekeeping |
| 203 | closed already |

## Open gaps

Nothing filed, nobody claimed:

- Issue #11 — Android "send to phone" still broken; owner acknowledged and no claim. Ours to take.
- Issue #209 — reinstall leaves no permission dialog; unclaimed, reproducible-by-report.
- Horn trigger (#26) — no usable signal found; nobody has a lead.
- Multi-axis acceleration trigger (#26) — listed as not done; needs a car to measure.
- Settings-restore write path beyond #138's three items (steering wheel, seat, mirror memory) — unasked.
- Smart-button rename/alias from #137 — the rename half is unclaimed even though hazard is blocked.
- Wake-lock / keep-awake budget after parking (#57 sub-question) — no measurement anywhere.
- Upload module decoupling rule (#57) — no interface exists yet, so any upload work is greenfield.
- Main-screen replacement content if #68 lands — undecided.
- `share/` has partial tests (`FileShareServerTest`, `LocalNetworkTest`) but nothing for the Android receive path — #11 needs a test before the fix (`measured`).
