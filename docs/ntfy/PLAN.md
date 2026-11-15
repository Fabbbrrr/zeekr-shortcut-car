# ntfy transport — build plan

Read `FINDINGS.md` (what the service does) and `DECISIONS.md` (what we chose and what it costs)
before starting. Nothing here is implemented yet; this file is the ticket list a context-free agent
can execute.

Ground rules for whoever picks this up:

- **Additive only.** New files for the ntfy side. The four shared files that must be edited —
  `alarm/SentryAlerts.java`, `AppConfig.java`, `settings/SettingsPreferenceFragment.java`,
  `res/xml/preferences.xml` — get mechanical edits, nothing else. Other agents work in this tree.
- Copy the licence header from any existing `.java` file into every new one (GPL-3.0-only, plus the
  NOTICE pointer).
- Strings in all three locales: `res/values/strings.xml` (Chinese, the default), `values-en`,
  `values-ms`. `ColorResourceParityTest`-style parity tests exist for some resources; check
  `app/src/test/java` before deciding whether a new parity test is needed.
- Every ticket ends with the two gates from `docs/polish/BASELINE.md`:
  `./gradlew :app:testDebugUnitTest --offline` and `./gradlew :app:assembleRelease --offline`.
  Baseline to match: 1873 tests, 0 failures (re-measure at your start, the number moves).

## Phase 0 — nothing in the app (done)

| Ticket | State |
|---|---|
| Measure the service, write `FINDINGS.md` | done |
| Decide the shape, write `DECISIONS.md` | done |
| `tools/ntfy_probe.py` — reproducible probe, `roundtrip` passes 8/8 | done |

## Phase 1 — alerts over ntfy (no commands, no account)

### T1 `alarm/NtfyRequest.java` (new, pure strings — testable without a network)

Build the three URLs and the header maps; no I/O.

- `topicLooksValid(String)`: `[-_A-Za-z0-9]`, length ≤64, ≥32 for a generated one
- `generateTopic()`: `zeekrsc_` + 40 chars from `SecureRandom` over `[A-Za-z0-9]` (≈239 bits)
- `textUrl(server, topic)` / `photoUrl(server, topic, caption, title, filename, tags)` using
  `URLEncoder` with `%20 → +`? **No** — ntfy decodes query values as URL-encoded text; use
  `URLEncoder.encode(value, "UTF-8")` and verify against the probe (`tools/ntfy_probe.py send`).
- `deepLink(server, topic, display)` → `ntfy://<host>/<topic>?display=<name>`
- `redact(String)`: same contract as `TelegramRequest#redact` — token never reaches a log line
- `Result` mirroring `TelegramSender.Result` (`ok`, `error`, `httpCode`, `rejected`), plus
  `tooLarge` for `413/41301` and `rateLimited` for `429`

Tests (`NtfyRequestTest`, JVM only): topic shape and entropy floor, generated names never collide in
10 000 draws, caption with `\n` is percent-encoded (`%0A`), `#`/`&`/`+` in a caption survive,
deep link format, oversized rejection maps to `tooLarge`.

### T2 `alarm/NtfySender.java` (new, blocking I/O — mirrors `TelegramSender`)

- `sendText(server, topic, token, text)` → `POST <server>/<topic>`, body = text, `X-Title`,
  `X-Priority: 4`, `X-Tags: car,warning`
- `sendPhoto(server, topic, token, jpeg, caption, title)` → `PUT <server>/<topic>?message=…&title=…
  &priority=4&filename=…&tags=…`, body = jpeg, `Content-Type: image/jpeg`
- `probe(server, topic, token)` → publishes a small message and returns the id (used by the wizard)
- `protectedTopic(server, topic)` → anonymous `POST …/publish?message=probe`; `403` means reserved
  (FINDINGS §1.5). Used only on the paid path.
- `account(server, token)` → `GET /v1/account`, returns username, tier limits, reservations
- timeouts as `TelegramSender` (15 s connect / 30 s read); never logs a URL with the token
- error mapping table: `401/403` → "not allowed", `404` → "topic or server wrong",
  `413/41301` → "photo too large for this plan", `429` → "too many alerts, try later",
  anything else → "network problem"

Tests: `NtfySenderTest` against a `HttpURLConnection`-shaped fake (the project already fakes network
calls somewhere — follow whatever `TelegramSender` tests do; if there are none, extract the response
mapping into a pure function and test that instead of inventing a server).

### T3 `alarm/AlertChannel.java` (new) + `alarm/TelegramChannel.java` (new)

Interface from DECISIONS D1; `TelegramChannel` is a thin adapter over the existing static methods in
`TelegramSender`, so behaviour does not change. No test beyond "the adapter calls the sender".

### T4 `AppConfig` — keys, all defaulting to today's behaviour

```
alerts_transport          "telegram" | "ntfy"      default: "telegram" when a Telegram token exists, else "telegram"
alerts_ntfy_server        "https://ntfy.sh"        default
alerts_ntfy_tier          "free" | "paid"          default "free"
alerts_ntfy_cmd_topic     ""                       phase 2
alerts_ntfy_cmd_since     ""                       phase 2, last seen message id
```
In `SECRETS_PREF_NAME` (not `app_config`): `alerts_ntfy_topic`, `alerts_ntfy_token`.

Tests: `AlertSecretsTest` grows two cases — the ntfy token **and** the topic are absent from
`SettingsBackup.snapshot`, and live in the secrets file; the server URL and tier live in `app_config`.

### T5 `SentryAlerts` — four call sites swap to the channel

`deliver` (989), `deliverText` (1097), `sendClip` (1159) — clip sending stays Telegram-only in v1 and
the row is hidden when the channel is ntfy; `sendTestPicture` (416) uses the channel.
`AlertPolicy` unchanged. Add the daily published counter from DECISIONS D6 (free tier only):
when today's count ≥ 220 and per-camera mode is on, fall back to the grid photo and note it in the
blackbox once.

Tests (`AlertChannelPolicyTest` or extend `AlertPolicyTest`): a 413 stops the loop after the first
photo; the daily-counter fallback keeps sending the grid photo instead of going silent; a failed
probe never turns an alert into silence.

### T6 Settings rows + wizard

`preferences.xml`: new group `cat_alerts_channel` and the rows in DECISIONS D11. Telegram rows keep
their keys and order.
`SettingsPreferenceFragment`: `bindAlerts()` (860) binds the chooser and the ntfy rows;
`showAlertsRows()` (1108) owns visibility; new `NtfySetupDialog` (new file in `settings/` or
`alarm/`) runs the 4-step wizard; QR through the existing `share/QrCode.encode` (ZXing 3.5.1 is
already a dependency).

Tests: `NtfyWizardStateTest` — pure state machine (steps, what each step requires, what "code
mismatch" does), so the wizard can be reasoned about without a screen. `SettingsVisibilityTest` if
the project has one for `showAlertsRows`; otherwise keep visibility logic in one static function and
test that.

### T7 Strings, three locales

`docs/ntfy/WIZARD-COPY.md` has the copy. Key names follow the existing `set_alerts_*` /
`opt_alerts_*` / `set_group_alerts_*` convention. No string may mention "ntfy.sh", "API", "token" or
"topic" on the free path — the free path only says "notification", "QR code", "code".

## Phase 2 — commands (separate release, separate decision)

### T8 Inbound poller

`alarm/NtfyCommandPoller` modelled on `TelegramPoller` (same shape: thread, `RequestPolicy`,
`since=<id>` cursor, `stop()` when sentry goes off). Vocabulary: `status`, `picture`, `help`.
Rules from FINDINGS §1.10–§1.12: unescape `\n`, sleep ≥1.5 s after any publish before polling,
store the cursor after dispatch, ignore ids ≤ cursor.
Tests: cursor advances past unanswered messages; a duplicate id is answered once; unknown text is
ignored silently; free tier requires the owner's warning toggle before it starts.

### T9 Action buttons on the alert

`NtfyRequest.actions(commandUrl)` → `view` (ntfy app) + `http` (one-tap "Take a photo now",
`method=GET`). Measured to work (§1.13, §1.14). Test: the action string is well-formed, ≤3 actions,
and the URL percent-encodes the command.

## Acceptance for the whole feature

1. Fresh install, free path, zero typing except the 4-digit code: alerts arrive on a phone with the
   ntfy app, photo included.
2. Existing install upgraded: still on Telegram, no setting changed, no new notification.
3. Switch to ntfy, then back to Telegram: both configs survive; the test button says which channel
   it used.
4. The ntfy token and topic appear in neither the settings export nor the diagnostics report
   (`AlertSecretsTest`).
5. A photo larger than the tier's limit fails with a sentence a non-technical owner understands, and
   the blackbox records the HTTP code.
6. Both gradle gates green, test count ≥ baseline with no failures.

## Effort, honestly

| Ticket | Rough size |
|---|---|
| T1 + T2 | 1 day, most of it the error mapping and its tests |
| T3 + T5 | half a day, the risky half (SentryAlerts is shared) |
| T4 | 2 hours |
| T6 | 1 day — the wizard is where a non-technical path is either real or theatre |
| T7 | half a day, three locales |
| T8 + T9 | 1 day, unchanged by the fact that the protocol is proven |

Phase 1 is a two-to-three day change for someone who already knows this codebase, and the parts that
can surprise you are the wizard copy and the tier limits — not the HTTP.

## What this plan does not do

- No removal of Telegram, no migration of stored tokens, no change to the alert triggers.
- No in-app account creation, no payment, no subscription management.
- No promises about iOS beyond what the docs say (FINDINGS §4.4).
- No default/shared topic constant anywhere in the source tree. If a review finds one, that is a bug.
