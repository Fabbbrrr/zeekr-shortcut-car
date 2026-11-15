# ntfy.sh as an alert transport — investigation and plan

**Status: planned, not implemented.** Nothing in `app/` was changed. This folder is one additive
change so it can be dropped or picked up without touching anyone else's work.

Question it answers: can parking alerts go somewhere other than Telegram, chosen in settings, for
people who would rather not use Telegram — and can that be set up by someone with almost no technical
knowledge?

## Verdict

Yes, and the free path is the interesting one.

| | Telegram | ntfy, free | ntfy, paid (Supporter $6/mo) |
|---|---|---|---|
| Account needed | bot token from @BotFather + chat id | **none** | ntfy.sh account + access token |
| Setup steps for a non-technical owner | install Telegram, message a bot, copy two strings into the car, press "find my chat" | install the app, scan a QR code on the car screen, type a 4-digit code | the above, plus pasting a token; protection needs a trip to the website |
| Picture per alert | yes, inline, kept forever | yes, attachment, expires 3 h (free) / 24 h (Support) | same, 25 MiB ceiling |
| Who can post to it | only the bot's chat | **anyone who learns the topic name** — disclosed in red, accepted by the owner before use | only that account |
| Message ceiling | none that matters | 250/day | 2 500/day |
| Delivery timing | push via Telegram | push via FCM; may be delayed unless the app's "instant delivery" is on | same |

The trade is plain: the free ntfy topic has no account, no signup and no token — the wizard can be a
QR code and four digits — but the topic name *is* the password, so it has to be long and random, and
anyone who learns it can read the photos and post fake alerts. Six dollars a month removes that.
Both paths are specified: `DECISIONS.md` D3 and D4.

**Owner's decision 2026-11-15: keep the free tier, photos included, and let the risk sit with the
owner.** The app's job is then disclosure that cannot be missed and a consent that is recorded, not a
quiet downgrade to text-only alerts:

- amber "public topic" line on the settings row, for as long as the free tier is on
- red confirmation dialog before the QR is even drawn, listing the six real failure modes (readable
  photos, fake alerts, the name on a lock screen, the third-party relay, 12 h / 3 h retention, nobody
  can prove who posted what), with a red "I understand, the risk is mine, use it anyway"
- the acceptance is stored per topic and per warning-text version, so a new topic or edited wording
  asks again, and a settings backup restored onto another car does not carry consent with it
- until it is accepted the car sends nothing, says so once in a local notification, and records each
  dropped alert in the blackbox — silence would be worse than a public topic

Details: `DECISIONS.md` D13, the exact words in `WIZARD-COPY.md`, the ticket in `PLAN.md` T6b.

## Unique topics

Asked for, and it is the right thing to ask: a shipped constant like `zeekr-alerts` would put every
owner on one topic. So:

- no topic name is ever compiled into the APK; each install generates one
- generated name = `zeekrsc_` + 40 characters from `SecureRandom` over `[A-Za-z0-9]` ≈ 239 bits
  (`tools/ntfy_probe.py topic`)
- ntfy has no directory of topics, so there is nothing to collide *with* except another owner who
  scanned the same QR — the wizard's "type the 4-digit code" step catches exactly that case
- a topic reserved on a paid plan cannot be taken or spoofed by anyone else, and the app verifies the
  reservation took effect with an anonymous publish (`403` = protected), rather than trusting a
  success message

## Files

| File | What it holds |
|---|---|
| `FINDINGS.md` | Every measured fact about the service, with grade (measured / single-source / unknown) and the command that produced it, plus the free vs paid tier table |
| `DECISIONS.md` | Twelve decisions, each with its cost, and four open questions for the owner |
| `PLAN.md` | Nine tickets with file lists, tests, the two gradle gates, acceptance criteria and effort |
| `WIZARD-COPY.md` | The exact wizard words in zh / en / ms, plus the HTTP-code-to-sentence table |
| `tools/ntfy_probe.py` | Re-measure everything: `topic`, `check`, `send`, `receive`, `roundtrip`, `limits` |

## What was actually tested

Against live `ntfy.sh`, on 2026-11-15, with a throwaway account and a throwaway token. No credential
is stored in this repo; `NTFY_TOKEN` comes from the environment.

`python tools/ntfy_probe.py roundtrip` → **8/8 pass**, covering the whole free-tier wizard path with
no account and no token: fresh topic, text alert, multi-line caption intact, priority intact,
attachment accepted, caption beside the attachment, attachment downloads back byte-identical,
`since=` scoping.

Also measured: the 2 MiB attachment boundary to the byte, `413/41301` on the other side of it, the
tier table and prices straight from the API, incremental command polling by message id, and an
`http` action button that publishes a command with one tap.

Not measured, and recorded as `unknown` rather than assumed: delivery to a real phone, notification
priority on the phone, the deep link actually subscribing the app, and creating a topic reservation
from inside the app (the account routes we probed answer 404/401). See `FINDINGS.md` §4 and §3.3.

Two quirks worth knowing before writing any code:

1. A multi-line caption next to a picture **must** go in the query string. A newline in a header is
   not legal HTTP, and the two-character `\n` sent in a header is stored literally as backslash-n.
2. What a poll returns is not byte-identical to what was sent: newlines come back escaped, and the
   cache is written slightly after the publish reply, so an instant poll can be empty. Neither is a
   lost message.

## Re-measuring

```bash
export NTFY_TOKEN=...              # optional; only the limits command needs it
python docs/ntfy/tools/ntfy_probe.py topic
python docs/ntfy/tools/ntfy_probe.py roundtrip
python docs/ntfy/tools/ntfy_probe.py limits
python docs/ntfy/tools/ntfy_probe.py check <topic>   # cached count + is it protected
```

`NTFY_SERVER` switches the whole probe to a self-hosted instance.

## Before this becomes a feature

Three questions in `DECISIONS.md` still need an owner's answer: per-camera mode on the free tier
(warn and allow, or force the grid photo), whether commands ship in the same release, and what the
settings row is called. The fourth — whether a public topic with readable photos is acceptable at all
— was answered on 2026-11-15: yes, keep it, owner's risk, gated by D13. Nothing in the plan depends
on that answer being revisited.
