# Slice NN — <package>

Auditor: <name/model>. Date: 2026-11-15. Files read: <count, list the ones cited>.

One section per finding, most serious first. Skip anything cosmetic unless it is cheap and visible.

## F-NN.01 — <one-line title>

- **Grade:** measured | confirmed | single-source | unknown
- **Severity:** P0 crash/data loss · P1 wrong behaviour in normal use · P2 edge case or rough edge · P3 polish/copy
- **Where:** `path:line` (never a whole file)
- **What happens:** mechanism in one or two sentences — what the code actually does, not what it should do.
- **Evidence:** the call site, the test, or the measurement that shows it. If none, grade `unknown` and say what would prove it.
- **Fix:** the smallest change that fixes the mechanism. Name the file and the function.
- **Cost / risk:** what could break, which existing tests touch it.
- **Test:** the test that would prove the fix, or `no unit test possible — needs device`.
