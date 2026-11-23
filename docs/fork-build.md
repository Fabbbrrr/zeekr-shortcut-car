# Fork build: version numbers, updates, releases

This fork exists to carry Fab's parked-alert work and to stay in step with upstream. Upstream
declined the pull request and reimplemented the idea itself, so the fork is kept and synced
rather than merged. These are the rules that keep that safe; they apply to the fork branch
(`feat/fork-build`) only and are not meant for upstream.

## Version numbers

| | versionName | versionCode |
|---|---|---|
| upstream | `4.12.1-alpha` | 371 |
| this fork | `4.13.0-fab-beta` | 400 |

- **The suffix carries a marker.** `VersionName.isFork` reads it, and it is the only thing that says
  which side published a build. The marker is **`alerts`** since 4.19; the old one, **`fab`**, is still
  accepted, and a name carrying both (`4.19.0-fab-alerts-beta`) is accepted too.
  `VersionName.FORK_MARKERS` holds the list; `VersionName.FORK_MARKER` is the current one.

  Why the old marker is kept: a car running `4.18.0-fab-beta` only accepts releases whose name
  carries `fab`. Drop it and that car sees no release ever again — the failure looks like "no update",
  not like an error. Cut a version with both segments while 4.18.0 builds are still on cars, and only
  remove `fab` from `FORK_MARKERS` once they are gone.
- **The number sits above the upstream release it contains.** Every time upstream comes in, the
  fork's second segment goes one above upstream's (upstream `4.12.x` → fork `4.13.0-fab-beta`).
  Within a fork line the patch segment counts up: `4.13.1-fab-beta`, `4.13.2-alerts-beta`.
- **versionCode keeps climbing** and never goes back to upstream's value: 394 was `4.5.21-beta`,
  this build is 400. A lower versionCode cannot be installed over a higher one.
- `app/src/test/java/com/kooo/evcam/update/ForkVersionTest.java` refuses to let a build go out
  with a missing marker or a versionCode that does not climb.

Why the marker exists: upstream's number is now *larger* than the fork's old numbering
(`4.12` vs `4.5`), so comparing numbers alone told a car that tracks the fork that upstream's
release was an update for it. Numbers cannot carry that decision; the marker does.

## Where updates come from

`update/UpdateSource` picks the repository, and it is **on by default**
(`AppConfig.isUpdateFromFork`, since 4.19). The switch lives on **Settings → Update & about**, not in
developer options: locking the developer interface must never point a car back at upstream, and
upstream's number is *larger* than this fork's, so a stray update check would install a build with
no alerts and no way to tell from the version numbers.

- on → the configured fork repository (default `Fabbbrrr/zeekr-shortcut-car`), and only tags carrying
  a marker are considered;
- off → `dts88/zeekr-shortcut-car`, and tags carrying a marker are ignored. **Turning it off asks
  first**, with a red dialog: the next update removes the alerts features and getting them back needs
  a manual install from the fork. Cancelling leaves it on.

Both rules live in one place, `VersionName.fitsSource`, called from `GithubReleases.fetchLatest`. So
no upstream release can ever be offered while the switch is on, whatever its number.

### Repository (`update_fork_repo`)

Text setting, default `Fabbbrrr/zeekr-shortcut-car`. Accepts `owner/repo` or
`https://github.com/owner/repo` and stores the `owner/repo` form; anything else (http, another host,
`git://`, an extra path segment) is refused with a message and **not stored** — a name that cannot be
pasted into the API URL only ever produces "no update".

A repository other than the default **waives the marker requirement** (`UpdateSource.fork`,
`VersionName.fitsSource(version, forkMarked, requireMarker)`), because another fork will not carry
our `-alerts` tag. The channel gate and the sha256 digest check (`DownloadIntegrity`) stay exactly as
they are: changing the repository changes *who we ask*, never *how we trust it*.

Saving a new repository clears the cached "fork latest" number, since that number came from the old
one.

### Channel (`update_channel`)

List: **Auto** (default), Alpha, Beta, Stable. Auto follows the suffix of the installed build —
installed `4.18.0-fab-beta` → beta, installed `-alerts-alpha` → alpha, no suffix → stable only — which
for today's builds is the same behaviour as before. It feeds `includeBeta`/`includeAlpha` through
`UpdateSource.gates` into `GithubReleases.fetchLatest` **for the fork path only**; the upstream path
keeps reading the two older switches (`update_include_beta`, `update_include_alpha`, the
"Versions to receive" row).

### The three numbers on the page

`pref_update_versions` shows the installed version **with its versionCode**, the latest upstream tag,
and the latest fork tag, so "how far behind" is visible without opening GitHub. Fetched in the
background on open (`update/LatestVersions`), cached in `AppConfig` under runtime keys
(`update_latest_upstream`, `update_latest_fork` — not exported, not settings), and on failure the row
says "could not check" without a dialog. Nothing on that path touches the UI thread.

## Releasing

There are two branches and they carry the same thing:

- `feat/fork-build` — where upstream is merged and everything is built and tested.
- `main` — what a visitor to the fork reads and downloads. It is fast-forwarded to
  `feat/fork-build` after each release, never merged into, so it cannot drift.

1. Sync: `git merge origin/main` on `feat/fork-build`, resolve, bump the version as above.
2. `./gradlew :app:testDebugUnitTest assembleRelease` — 2200+ unit tests, no failures.
3. Commit, push the branch.
4. Fast-forward `main` to the same commit and push it: `git push fork feat/fork-build:main`.
   (The Alerts feature reaches `main` this way, not by merging `alerts/01`…`alerts/04` — those are the
   split-PR versions of the same work, older than what shipped, and merging them would walk the code
   back.)
5. **Tag the merge commit, not `main`.** This is the mistake that produced `v4.5.18-beta` …
   `v4.5.21-beta` all pointing at `1e591f4f` (Release 4.4.1-alpha): the tags were made while
   `main` was checked out, so those four tags point at the wrong commit and name the wrong
   version.
   ```
   git tag v4.19.0-fab-alerts-beta <merge-commit>
   git push fork v4.19.0-fab-alerts-beta
   ```
   Both markers in the tag name while 4.18.0 builds are still on cars: the new one is what this build
   is called, the old one is what those cars can see.
6. **The release must be published, not left as a draft.** `.github/workflows/build.yml` creates
   releases as drafts (upstream's policy: nothing public). The app's update check uses the
   anonymous GitHub API, which never returns drafts — a draft release is invisible, so the car
   reports "no update" forever. The fork branch sets `draft: false`: a tag push publishes the
   release the car can see. If a release does go out as a draft, publish it by hand before
   expecting any car to find it.

## Known gaps

- The Alerts screens and their settings rows have no Arabic yet (`StringResourceParityTest`
  records the exact count under `PENDING_ARABIC`). With Arabic selected those rows fall back to
  the default resources, which are Chinese. The same is true of the 4.19 update rows and the
  developer-options entry on About (28 strings, 435 → 463).
- Release notes generated by the workflow still link to upstream's wiki and issue tracker.
