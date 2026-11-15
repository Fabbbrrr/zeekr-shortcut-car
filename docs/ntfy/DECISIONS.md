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

## D3 — Free path: no account, no token, no e-mail. Just a generated topic.

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
watching cleartext DNS. This is the honest trade of the free tier and the wizard says so in one plain
sentence. Closing it costs $6/mo (D4).

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

## D11 — What the settings page looks like (same shape as Telegram, one extra choice)

New group `cat_alerts_channel` above the existing Telegram group:

| Row | Free path | Paid path |
|---|---|---|
| `pref_alerts_channel` | "Where alerts go: Telegram / ntfy (free) / ntfy (my account)" | same |
| `pref_alerts_ntfy_setup` | "Set up ntfy" → wizard | same |
| `pref_alerts_ntfy_topic` | shows `…aB3d` (last 4) | same |
| `pref_alerts_ntfy_account` | hidden | shows the tier, e.g. "Supporter — 3 reserved topics" |
| `pref_alerts_ntfy_token` | hidden (no token needed) | "Access token", last 4 shown |
| `pref_alerts_ntfy_server` | hidden unless "advanced" | same |
| `pref_alerts_test` | existing row, now sends over the selected channel | same |

Rows appear only for the selected channel — the rule the settings page already follows (ui-spec §3:
visibility decided in one place).

**Cost.** `showAlertsRows()` (line 1108) and `preferences.xml` (~line 180) are shared files; the
Telegram rows must not move or rename, because a current owner's settings must survive the update.
The transport chooser defaults to Telegram whenever a Telegram token exists.

## D12 — Nothing is deleted, nothing migrates on its own

An existing install keeps sending to Telegram after the update. The chooser starts on Telegram, the
ntfy rows start hidden. If the owner switches, the Telegram token stays stored so switching back
costs nothing.

**Cost.** Two configurations can be present at once; only the selected one sends. The test button
says which channel it used ("sent via ntfy"), so nobody has to guess.

## Open questions for the owner

1. Do we accept a **public** free topic at all, given the photos are readable by anyone who learns the
   name? (The alternative is free tier = text-only alerts, which removes the point.)
2. Free tier, per-camera mode: warn and allow, or force the grid photo?
3. Ship phase 2 (commands) in the same release, or alerts only first?
4. Name in the settings UI: "ntfy" as-is, or "ntfy (phone notifications)" for people who have never
   heard of it?
