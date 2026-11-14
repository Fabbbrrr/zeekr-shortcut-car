# Polish loop — branch `feat/fork-polish`

Branched from `feat/fork-build` at `c1d7fba1` (the branch the last fork release, `v4.5.2-beta`,
was cut from). Running as a long loop: audit → document → decide → fix → verify → commit.

## Method

1. **Baseline first** — build and full unit-test suite green at the branch head before any change.
   Numbers recorded in `BASELINE.md`.
2. **Audit by slice.** One auditor per package slice, disjoint file lists. Read-only on source:
   auditors write only their own file in `findings/`. Findings are graded, never ungraded.
3. **Decide.** A finding becomes a fix only when it is reproducible from the source or a test, or
   the mechanism is stated in one line with a `path:line`. Judgement calls go in `DECISIONS.md`
   with what the fix costs.
4. **Fix, one commit per fix.** Test with the fix where a test is possible. No commit without the
   build and the suite green (measured, not assumed).
5. **Adversarial review of the diffs** before pushing, then push the branch.

## Gates (must be green at every commit)

- `./gradlew :app:assembleRelease --offline` → exit 0
- `./gradlew :app:testDebugUnitTest --offline` → exit 0, no test deleted or weakened to pass
- No secrets in the diff; no fork-only bits added to what the PR branch would carry.

## Grades

Every claim carries one: `measured` (read off the running build/test), `confirmed` (two independent
sources in the tree), `single-source`, `unknown` (a gap written down honestly). An ungraded fact is
a guess and does not justify an edit.

## Layout

- `BASELINE.md` — the starting measurements and the test/build picture.
- `FINDINGS.md` — index of all findings, one row each, with status.
- `findings/NN-slug.md` — the auditor's detail per slice (template in `_TEMPLATE.md`).
- `DECISIONS.md` — what was decided, why, and what it cost.
- `CHANGELOG-polish.md` — what actually shipped on this branch, one entry per commit.
