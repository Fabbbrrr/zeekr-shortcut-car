# ntfy transport — decisions, with what each one costs

Scope: a second way to deliver parking alerts, chosen in settings, offered as *free* and *paid*
variants. Telegram stays exactly as it is. This file records the choices and the price of each one.
`PLAN.md` turns them into tickets. Facts come from `FINDINGS.md`; every claim below that leans on a
measurement names the row.

Status: **planned, not implemented.** No production file was touched.

## D1 — One seam in front of the sender, not a second copy of the alarm logic

`alarm/SentryAlerts` decides *whether* to alert. It also currently talks Telegram directly in four
places: `deliver(...)` (line 989), `deliverText(...)` (1097), `sendClip(...)` (1159) and the wizard
calls `sendTestPicture` (416) / `checkToken` (450) / `findChats` (656). Add a small interface

```java
interface AlertChannel {
    Result sendPhoto(byte[] jpeg, String caption, String extra);   // one picture + caption
    Result sendText(String text);                                  // caption only, no picture
    Result probe();                                                // wizard: "is this set up right?"
}
```

with `TelegramChannel` (the existing `TelegramSender`/`TelegramRequest`, unchanged) and
`NtfyChannel` behind it. `SentryAlerts` keeps every policy decision it already makes.

**Cost.** `SentryAlerts` is 1 243 lines and every alert path runs through it; this is the one shared
file the change must edit, so it collides with any other agent working in `alarm/`. Keep the edit
mechanical (call the channel instead of the sender) and put all new logic in new files.
The alternative — a parallel `NtfyAlerts` class — duplicates the cooldown, the hourly cap, the
arm delay, the quiet hours and the picture pipeline, and guarantees the two paths drift apart.

## D2 — One topic for alerts, a second topic only if the owner wants commands

The alert topic is written by the car and read by the phone. Commands run the other way, so they need
their own topic: the car polls it, and if the two were shared the car would read back its own alerts
as commands. Measured: `?poll=1&since=<id>` returns only newer messages (§1.12), so a command loop is
a topic plus one stored message id.

**Cost.** Two topic names to keep, two rows in the settings page. Free tier: two public names instead
of one. Commands stay **off by default** and are their own later ticket, so v1 has one topic.

## D3 — Free path: no account, no token, no e-mail. Just a generated topic. (Decided: photos stay.)

Measured: publishing needs no credential at all (§1.2), and the account's token buys nothing on a
public topic. So the free wizard asks for nothing a non-technical owner cannot do:

1. car generates `zeekrsc_` + 40 random characters from `[A-Za-z0-9]` (≈239 bits, §1.3 for the shape)
2. screen shows a QR code of `ntfy://ntfy.sh/<topic>?display=Zeekr` and a "copy link" button
3. owner installs the ntfy app, scans the QR, and the app is subscribed
4. car publishes a test alert carrying a 4-digit code; the owner types the code back
5. done

Step 4 exists because nothing in the protocol says the phone received anything (§4.5 unknown). It is
the difference between "we posted it" and "you saw it".

**Cost.** Free topics are public: whoever learns the name can read the photos and post fake alerts
(§1.4). The name is unguessable, but it is visible on a lock screen, in the app, and to anyone
watching cleartext DNS. Closing it costs $6/mo (D4).

**Owner's decision (2026-11-15): keep photos on the free tier.** The risk is the owner's to carry,
not something the app hides or quietly prevents. What the app owes in return is disclosure that
cannot be missed and an acceptance that is recorded — D13. Text-only free alerts are **not** the
plan; do not implement a downgrade nobody asked for.

**No default topic in the APK, ever.** A shipped constant like `zeekr-alerts` would put every owner
on one topic. Each install generates its own; the string is stored per device.

## D4 — Paid path: the owner's ntfy.sh account token, and what it actually buys

Wizard step: "I have an ntfy.sh account" → paste an access token → `GET /v1/account` verifies it and
returns the tier (§2, §3). Then:

- `reservations > 0` (Supporter $6/mo and up): offer "protect this topic". The topic name becomes
  writable only by that account, which is what stops fake alerts. Verified equivalent: an anonymous
  publish to a reserved topic answers `403` (§1.5) — that is also the app's check that protection
  took effect.
- `reservations == 0` (free account with a token): say so plainly — "your plan has no topic
  reservations" — and either stay on the free public topic or point at the upgrade page.
- larger photos and a longer cache come along for free (2 MiB → 25 MiB, §2).

**Cost / limit.** Creating a reservation from inside the app is **not reachable today**: the account
routes we probed answer 404/401 (§3.3), and the web app reserves through a route we have not
reversed. So v1 has two honest options and must pick one at build time:

- v1a: the wizard walks the owner to the ntfy.sh website to reserve, then has them paste the topic
  back and *verifies* it with the 403 probe. More taps, zero unknown dependencies.
- v1b: someone reverses the reservation call first and the app does it in one tap.

Take v1a. It needs no paid account on this box to build or test, and it degrades to a clear message
if the API is missing.

**No in-app signup.** Creating an ntfy.sh account needs an e-mail round trip; that is a browser job,
not a car-screen job. The wizard hands over a link.

## D5 — Self-hosted server address is one text field, off by default

`https://ntfy.sh` is a default, not a constant: one `alerts_ntfy_server` string, validated with the
same probe. Everything else (topic, token, paths) is server-relative.

**Cost.** A self-hosted server has no Firebase, so the phone needs the app's WebSocket service on
(§4.3) and possibly its own auth. The wizard hint says "self-hosted: turn on instant delivery in the
ntfy app". Out of v1 acceptance tests (no self-hosted instance on this box).

## D6 — Attachments: one JPEG per alert, hard-capped, sized to the cheapest tier

The picture path already compresses to ≤900 kB per photo (§ docs/sentry-alerts-spec.md §3.5) and the
free tier allows 2 MiB (§2.1). So the same bytes fit both transports; no re-encode per transport.

Guard rails, in order: refuse locally over the tier's limit; map `413 / 41301` to a plain sentence
("photo too big for this plan"); never retry a 413.

**Cost.** Per-camera mode sends four photos per alert. On the free tier the daily message budget
(250) can be passed: worst case 6 alerts/hour × 24 h × 4 photos = 576. Two consequences: the wizard
defaults per-camera mode **off** when the tier is free, and `AlertPolicy` gains one more counter —
published messages today — so the car degrades to one grid photo instead of going silent. Telegram
has no such cap, so this counter only applies to ntfy.

## D7 — Caption formatting: query string for pictures, body for text

Measured: a real newline in a header is impossible, `\n` in a header stays literal, and a multi-line
caption beside a file must travel in the query string (§1.8, §1.9). So `NtfyRequest` builds:

- text only → `POST /<topic>`, body is the text, real newlines intact
- picture → `PUT /<topic>?message=<urlencoded>&title=<…>&priority=4&filename=<…>&tags=car,warning`

**Cost.** One URL carries the caption, so it is the thing that shows up in the module log. It is not
secret beyond the topic name (the topic is already the password), but the redaction rule from
`TelegramRequest#redact` must still be applied to anything written down.

## D8 — Reading messages: unescape, and never poll straight after publishing

A cached message comes back with `\n` escapes where the sent one had line breaks (§1.10). The cache
is also written asynchronously, so an instant poll can return nothing (§1.11). Rules for the command
loop and for the wizard's verification step: sleep ≥1.5 s, unescape, and treat an empty poll as "not
yet", never as "lost".

**Cost.** One `Thread.sleep` in a background thread and a two-line unescape. The alternative, a live
streaming subscription (`/json` without `poll=1`, long-lived connection), has to survive the car's
sleep cycles and CGNAT timeouts; a poll loop is duller and easier to reason about.

## D9 — Commands work, but they are phase 2

The mechanism is proven: poll `?poll=1&since=<id>`, and an `http` action button on the alert can
publish a command with one tap (§1.13, §1.14). That is *better* than typing `/picture` in Telegram —
the owner taps the notification they just received.

**Cost.** Without a reserved topic there is no identity on an inbound message: anyone who knows the
command topic can post `take_photo`. Message content can carry a shared word, but that word rides in
the very notification anyone reading the topic can see. So:

- free tier: commands **off unless** the owner turns them on with a warning; commands are a fixed
  vocabulary (`status`, `picture`, `help`), never free text, and `RequestPolicy` (30 s / 6 per hour)
  still applies
- paid tier: reserved command topic, only that account's tokens can write it

Phase 1 ships alerts only. Nothing in phase 1 depends on phase 2.

## D10 — Secrets: the token joins the Telegram token in the same private file

`AppConfig.SECRETS_PREF_NAME` (`app_config_secrets`) already holds the Telegram token and chat id and
is excluded from the settings export, diagnostics and blackbox (`AlertSecretsTest`). The ntfy token
goes there. The **topic** is not a credential in the same sense, but it is the password: put it there
too, and show only the last 4 characters in the settings row, exactly like the chat id.

**Cost.** Any new key added to `app_config` would leak through `SettingsBackup.snapshot`, which
exports every unknown key by default. That is why this is a decision and not a preference.

## D11 — What the settings page looks like (one toggle, one status line, nothing hidden that isn't dev)

There is no "where should alerts go?" chooser for owners. There is one **developer toggle** between
the two transports, and one **status line** that is always visible:

| Row | Where | What it does |
|---|---|---|
| `pref_alerts_channel_status` | alerts section, visible to everyone, not a control | "Alerts go to: Telegram" / "Alerts go to: ntfy (public topic)" — reads the *effective* transport, D14 |
| `pref_dev_alerts_ntfy` | developer section, only when developer mode is unlocked | switch: off = Telegram, on = ntfy. Writes `alerts_transport` (D14) |
| `pref_alerts_ntfy_setup` | alerts section, gate open only | "Set up ntfy" → wizard |
| `pref_alerts_ntfy_topic` | gate open only | shows `…aB3d` (last 4) |
| `pref_alerts_ntfy_account` | gate open, paid path only | tier, e.g. "Supporter — 3 reserved topics" |
| `pref_alerts_ntfy_token` | gate open, paid path only | "Access token", last 4 shown |
| `pref_alerts_ntfy_server` | gate open, advanced only | server address |
| `pref_alerts_test` | existing row, unchanged | sends over whichever channel is effective, and says which |

Group headers get a one-line subtitle for the side that is not in use — "not in use: alerts go to
ntfy" on the Telegram group, the reverse on the ntfy group. Rows of the inactive side stay reachable
and editable: nothing here is deleted by switching, and a row that exists but cannot be edited is how
people lose settings.

**Cost.** `showAlertsRows()` (line 1108) and `preferences.xml` (~line 180) are shared files; the four
existing Telegram rows must keep their keys and order, because a current owner's settings must survive
the update. The status row is the only new always-visible row, and it must never lie about which
channel is sending — it reads the same derived value the sender uses, not the stored one.

## D12 — Nothing is deleted, nothing migrates on its own

An existing install keeps sending to Telegram after the update: `alerts_transport` defaults to
`telegram` and, while developer mode is closed, it reads as `telegram` no matter what is stored (D14).
Both sides keep their configuration permanently — the Telegram token and chat id, the ntfy topic,
token, server, tier and acknowledgement. **Switching transports writes exactly one key and deletes
nothing**; switching back restores the previous side exactly as it was, including not re-asking the
free-plan warning for a topic that was already accepted.

**Cost.** Two complete configurations can be present at once; only the effective one sends. The test
button says which channel it used ("sent via ntfy"), and the status row says which one is live, so
nobody has to guess from storage.

## D13 — The free-topic risk is disclosed in red and accepted before the first alert

Decided (owner, 2026-11-15): the free tier keeps the photos, and the owner carries the risk. So the
app must make the risk impossible to miss and must record that it was accepted.

**Two surfaces, two severities.**

- The free-tier row in settings carries a **yellow** dot/line and the words "public topic" — amber,
  `R.color.caution`, visible every time the owner looks at the page. Amber, not red: it is a standing
  fact, not an action being taken right now.
- Every path that turns the free tier on passes a **red** confirmation dialog, placed immediately
  after choosing the free option and **before** the QR is drawn or anything is published: the reasons,
  then a button that says what it does ("I understand, the risk is mine, use it anyway"). Red via
  `ui/CamDialogs.showDestructive`, the same treatment the app already gives irreversible operations,
  because this is not a normal "OK".

**The reasons are a list, not a sentence.** Someone accepting must be able to read the actual failure
modes: the photos are readable by anyone who learns the name; fake alerts can be posted; the name is
visible on a lock screen and in the app; the photos leave the car and pass through a third party
(ntfy.sh and its push provider); free-tier messages keep 12 h and pictures 3 h; nobody can prove
later who published what. The full copy is in `WIZARD-COPY.md`.

**The acceptance is recorded, per topic, per wording version.** `alerts_ntfy_risk_ack` in
`app_config` holds `<warning-version>|<last 8 of topic>`. The wording version is a constant in code
(`NtfyRisk.WARNING_VERSION`), so a future edit to the warning text forces a fresh acknowledgement
instead of inheriting consent for words the owner never read. Changing the topic or the server clears
the match, so a new topic is a new decision.

**What happens when it is missing.** The car does **not** send. It raises one local notification
("alerts are not being sent: confirm the free-plan warning") and writes a blackbox line per dropped
alert. Silence would be the one outcome worse than a public topic — an owner who believes the car is
watched when it is not.

**Cost.** Three taps more on the free path, one more stored key, one dialog that has to be written
carefully in three languages. It also means the free tier can never be enabled by a restore from a
backup made on another car: the ack carries the topic, so it correctly fails to match. That is the
intent, not a bug.

**Sending stays the owner's responsibility afterwards.** The dialog is a gate, not a guarantee: it
does not encrypt anything, does not hide the topic from the lock screen, and cannot stop a fake alert
arriving. The paid path is the only thing that closes the hole (D4).

## D14 — ntfy is a developer toggle, and switching transports erases nothing (decided 2026-11-15)

The transport ships dormant and selectable by a developer, with one rule that carries the weight:
**the toggle chooses which side is active; it never clears either side.**

**One key does the whole gate.** `alerts_transport` ∈ `{"telegram", "ntfy"}`, default `"telegram"`,
listed in `AppConfig.DEVELOPER_KEYS`. That single fact gives three things at once:

- while developer mode is locked, the key **reads as unset** → the effective transport is Telegram,
  whatever is stored (this is the existing `readsAsUnset`/`readEnum` mechanism, not new code)
- while developer mode is unlocked, one switch in `screen_developer` flips it: off = Telegram, on =
  ntfy. It is the transport selector *and* the feature flag; there is no second boolean to keep in
  sync and no half-configured state to invent names for
- switching is a one-key write. The Telegram token and chat id, the ntfy topic, token, server, tier
  and the free-plan acknowledgement all stay where they are. Flipping back restores the previous side
  exactly, and does not re-ask a warning that topic already accepted

`DeveloperSettingsTest` walks `screen_developer` row by row, so the toggle row gets its parity test
from the existing harness. No `BuildConfig.DEBUG` check, no build constant: the release APK that ships
is the one you test on the car.

**What the gate hides.** With `alerts_transport` reading as `telegram`, the ntfy rows, the wizard, the
QR step, the code step and the red free-plan warning are all unreachable, and the transport has no
ntfy option to select. The status row still works and says "Alerts go to: Telegram".

**Turning the toggle on with no ntfy configuration** runs the wizard immediately (red warning → QR →
code). If the owner backs out before a topic is stored, the switch returns to off — there is no state
where ntfy is selected but not set up.

**Turning it off** needs nothing from the ntfy side: telegram is now the effective transport. If there
is no Telegram token, the existing "token not filled in" messages do the explaining (the same ones a
fresh install already gets); nothing is guessed or auto-created.

| stored | developer mode | what actually sends | what the status row says |
|---|---|---|---|
| telegram | locked or unlocked | Telegram | "Alerts go to: Telegram" |
| ntfy | unlocked | ntfy | "Alerts go to: ntfy (public topic)" / "(protected topic)" |
| ntfy | locked | **Telegram** | "Alerts go to: Telegram" |

The third row is the whole reason for using `DEVELOPER_KEYS`: a hidden feature must never leave an
owner silently un-alerted, and the status line must never say ntfy when Telegram is the one sending.

**The free-plan warning is not bypassed in dev.** The gate you test is the gate that ships — six
reasons, red button — at the cost of one extra tap per regenerated topic.

**Cost.** One switch that does two jobs ("which transport" and "is the experiment available") is
honest here only because the two job descriptions are the same thing; if a third transport ever
arrives, this becomes a chooser again and the toggle retires. A dormant topic and access token stay in
the secrets file while the toggle is off — not a leak (that file never leaves the device), but "off"
is not "forgotten", and the docs must keep saying so.

**Graduating out of dev.** Remove the row, remove `alerts_transport` from `DEVELOPER_KEYS`, and the
same key becomes the owner-facing chooser it already stores the value for. Nothing about a car's
stored configuration changes on that day.

## Open questions for the owner

1. ~~Do we accept a public free topic at all?~~ **Answered 2026-11-15: yes, keep it, owner's risk,
   gated by D13.**
2. Free tier, per-camera mode: warn and allow, or force the grid photo? (D6 currently proposes warn
   and allow with a silent fallback when the daily budget runs low.)
3. Ship phase 2 (commands) in the same release, or alerts only first?
4. Name in the settings UI: "ntfy" as-is, or "ntfy (phone notifications)" for people who have never
   heard of it?
