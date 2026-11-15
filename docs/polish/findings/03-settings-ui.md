# Slice 03 — settings / ui

Auditor: pi (claude). Date: 2026-11-15. Files read: ~75 — all 31 `.java` under `app/src/main/java/com/kooo/evcam/settings/`, all 26 under `ui/`, their 20 tests, `res/xml/preferences.xml`, the layouts they inflate, `docs/ui-spec.md`, `docs/ui-text-style.md`. Cited: `settings/ProfileEditorFragment.java`, `settings/SettingsPreferenceFragment.java`, `settings/SettingsHeadersFragment.java`, `settings/SettingsRegistry.java`, `res/layout/layout_status_bar.xml`, `app/src/test/java/com/kooo/evcam/settings/SettingsShotsTest.java`.

Both specs were read first and treated as the contract. The packages are unusually clean: keys, defaults (HUD ranges in `AppConfig.java:1604-1622` match `docs/ui-spec.md` §九), string/colour parity gates, listener register/unregister pairs, and the `findPreference` literal gate all check out. What follows is what is left.

## F-03.01 — Editor pane runs Camera2 and storage calls on the main thread

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/settings/ProfileEditorFragment.java:904` (`getCameraCharacteristics`), reached from `:886`/`:566`; storage at `:288`, `:542` → `settings/RecordingBudget.java:149-152`
- **What happens:** Every render of the profile editor — including the one that runs after each pick — calls `ProfileValidation.check(profile, capabilities())`. The capabilities callback calls `cameraManager.getCameraCharacteristics(cameraId)` once per role, synchronously, on the UI thread. The same render then stats the SD-card root (`StatFs`) on the UI thread. A cold `getCameraCharacteristics()` or a first statfs on a removable volume costs more than one frame, so the row you just tapped visibly hesitates.
- **Evidence:** Call chain read in the tree: `renderIssues()` (`:566`) → `capabilities()` (`:884-886`) → `declaredSizes()` (`:897`) → `:904`; `offeredSizes()` (`:796`) does it again per picker. No cache, no executor. Cost itself is not measured in this repo — hence `single-source`.
- **Fix:** Cache the characteristics map per `cameraId` for the life of the fragment (fill it once in `onViewCreated` off the main thread; `declaredSizes` reads the cache). Keep the storage figure from the snapshot the status bar already uses instead of statfs-ing in the render path.
- **Cost / risk:** Low. `ProfileEditorRebaseTest` covers the merge logic, not timing; `SettingsShotsTest` renders this pane and would catch a stale cache only if the assertion set grew.
- **Test:** Robolectric: render the pane twice with a stub `CameraManager`, assert the characteristics query count is 1.

## F-03.02 — Background thread reads fragment state, then `requireActivity()`

- **Grade:** single-source
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java:490-504`
- **What happens:** `updateFootageLocked()` counts locked files on a raw thread, guards with `if (!isAdded()) return;` on that thread, and on the next line calls `requireActivity().runOnUiThread(...)`. Both are fragment-state reads off the main thread. If the fragment is detached between the check and the call — the settings shell replaces this fragment on every section change (`SettingsShellFragment.java:151-158`) — `requireActivity()` throws `IllegalStateException`.
- **Evidence:** Read in the tree; the replace-on-section-change path that creates the window is `SettingsShellFragment.showSection` (`:151`). The race is narrow, not measured.
- **Fix:** Capture the activity (or `view.post`) before starting the thread and do the `isAdded()` check inside the main-thread runnable, not on the worker.
- **Cost / risk:** Trivial; no test touches this path.
- **Test:** no unit test possible — needs device (or a Robolectric test that detaches the fragment while the worker runs).

## F-03.03 — Left column state can go stale while settings is on screen

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/java/com/kooo/evcam/settings/SettingsHeadersFragment.java:72-80` (`onResume` → `refreshStatus()`), `:109` `refreshStatus()`
- **What happens:** The spec says the left column follows the real state *while the settings page is visible* — recording starting, USB stick pulled (`docs/ui-spec.md:792`). The code rewrites those status lines only on `onResume` and after a change made inside this shell (`SettingsShellFragment.java:267` `refreshHeaders`). A recording that starts from the floating button, a smart button, or the notification — all reachable with settings on screen — leaves the header saying 「未录制」 until you leave and come back. Same for the storage line when the card is pulled; `StorageState.current()` has updated, nothing re-reads it.
- **Evidence:** `docs/ui-spec.md:792` against `grep refreshStatus` — the only callers are `onResume`, `build()` and the shell's post-change path. The status bar cells do update live, so the mechanism exists one package over.
- **Fix:** Register the existing `AppConfig.onChanged(...)` callback (recording key + storage snapshot callback) in `SettingsHeadersFragment` and call `refreshStatus()`; unregister in `onDestroyView`.
- **Cost / risk:** Low; a rebuild per change is cheap (a handful of rows). `SettingsShotsTest` renders the headers and would catch a broken string path.
- **Test:** Robolectric: open settings, flip a watched key through `AppConfig`, assert the header summary changed without a resume.

## F-03.04 — Clickable status-bar cells have a 40 dp hit height

- **Grade:** confirmed
- **Severity:** P2 edge case or rough edge
- **Where:** `app/src/main/res/layout/layout_status_bar.xml:27` and `:43` (`tv_status_stream`, `tv_status_storage`)
- **What happens:** Both cells are the primary "state is clickable" affordance (`docs/ui-spec.md` §十三 状态能点: the recording-rate segment goes to 录制 → 录制画质, the storage segment to 存储 → 存储位置). They carry `selectableItemBackground` but `android:minHeight="40dp"` inside a 48 dp bar, so the top and bottom 4 dp of the bar do not respond to a touch, and the highlight does not fill the band. Every other interactive surface in settings is at least 48 dp (`pref_category_link.xml:11`, `item_vehicle_info_row.xml:29`, `pref_row_value_action.xml:82`).
- **Evidence:** Layout values read against the spec's own minimum for tap targets (`docs/ui-spec.md:132` `icon_button_size` 48dp, `:221` buttons 48dp high). Nothing in the gates checks hit height — `LayoutHygieneTest` and `SettingsRowAlignmentTest` cover button type, tint and text start, so a smaller target cannot fail the build.
- **Fix:** `minHeight` → `@dimen/status_bar_height` on both cells in `layout_status_bar.xml`.
- **Cost / risk:** None known; the cells are `wrap_content` today so they grow into the bar. `SettingsShotsTest` screenshots will change.
- **Test:** add a hit-height check to `SettingsRowAlignmentTest` for layouts whose roots have a click handler.

## F-03.05 — Registry comment contradicts the test on who sees the drive-sound picker

- **Grade:** confirmed
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/settings/SettingsRegistry.java:203`
- **What happens:** The comment records 「对所有人固定为车内…车外只在开发者选项「声音」一组里选得到」 (decided 2026-11-12). The next decision (2026-11-13) put 听感 back for everyone, which is what the code does and what the test pins. The stale line is the one a reader trusts when changing who sees what.
- **Evidence:** `app/src/test/java/com/kooo/evcam/settings/SettingsShotsTest.java:145` `driveSoundRowsAreThereForEveryone` asserts `pref_drive_sound_view` is shown with developer mode off — the opposite of the comment.
- **Fix:** Rewrite the comment line to the 2026-11-13 decision (or drop it; the test is the contract).
- **Cost / risk:** None.
- **Test:** existing test covers the behaviour.

## F-03.06 — Open settings dialogs are destroyed by a configuration change and their pending edits revert

- **Grade:** single-source
- **Severity:** P3 polish/copy
- **Where:** `app/src/main/java/com/kooo/evcam/settings/SliderDialog.java` (`show(...)` dismiss-restore block) and `ui/CamDialogs.java`
- **What happens:** Settings dialogs are plain `AlertDialog`s, not `DialogFragment`s, so an activity recreate closes them without the save button firing. `SliderDialog` then runs its cancel path and writes each moved slider back to the value it opened with. Practical trigger on the car: the day/night colour mode flipping (or a language change) while a HUD slider dialog is open — the dialog vanishes and the drags you just made are gone.
- **Evidence:** Read in the tree: `CamDialogs.show` returns an `AlertDialog`; the cancel restore is the `setOnDismissListener` block. Not measured on device.
- **Fix:** Either keep the pending values in the dialog's saved state, or treat "dismissed without save" as save for `continuous` sliders (they already persisted on each drag).
- **Cost / risk:** Changing dismiss-means-save would break 「取消、返回键回到打开时的值」 for the cancel button too if done carelessly — keep cancel, handle recreate only.
- **Test:** no unit test possible — needs device (recreate with a dialog open).
