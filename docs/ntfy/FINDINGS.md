# ntfy.sh — measured facts

Everything below was measured against the live service on 2026-11-15 from `lai`, unless the grade
column says otherwise. Nothing here is from memory.

Grades follow the project rule: **measured** (read off the running thing), **confirmed** (two
independent sources), **single-source** (one source, usually the vendor's own docs), **unknown**
(nobody knows yet — it stays unknown until someone measures it).

Re-run any row with `python docs/ntfy/tools/ntfy_probe.py <command>`.

## 1. Transport

| # | Fact | Grade | Evidence |
|---|---|---|---|
| 1.1 | A topic is created by the first publish or subscribe. No sign-up, no account, no API key. | measured | `POST https://ntfy.sh/<fresh-topic>` → `200 {"id":…,"event":"message"}`, then `?poll=1&since=all` returns it |
| 1.2 | Publishing works with **no** credential at all. A bearer token is accepted but is not required for a public topic. | measured | same publish with and without `Authorization: Bearer <token>` → both `200` |
| 1.3 | Topic names accept `[-_A-Za-z0-9]`, up to 64 characters. | single-source | docs `.md` "Picking a topic"; every name we sent matched that shape |
| 1.4 | The topic name is the only access control on a free topic. Anyone who knows it can publish **and** read. | measured | `POST /<our-topic>/publish` from an unauthenticated client → `200` |
| 1.5 | A protected (reserved) topic refuses an anonymous publish with `403 {"code":40301}`. Reading may still be allowed (it is on `announcements`). | measured | `POST /announcements` → `403`; `GET /announcements/json?poll=1` → `200` |
| 1.6 | Text publish: body carries the message; real newlines survive into the publish reply. | measured | `POST` body `a\nb\nc` → reply `"message":"a\nb\nc"` |
| 1.7 | Attachment publish is a raw `PUT`; the body **is** the file. There is no multipart form, so a caption cannot travel in the body. | measured | `PUT -T file.jpg -H "Filename: x.jpg"` → `attachment:{"name":"x.jpg","type":"image/jpeg","size":…,"url":"https://ntfy.sh/file/<id>.jpg"}` |
| 1.8 | A multi-line caption beside a file goes in the **query string** (`?message=…%0A…`). Measured: it arrives with real line breaks. | measured | `PUT /<topic>?message=a%0Ab%0Ac&filename=p.jpg` → `"message":"a\nb\nc"` |
| 1.9 | A caption in the `X-Message` header cannot contain a real newline — HTTP clients refuse to send the request (`ValueError: Invalid header value`). The 2-character sequence `\n` sent in a header is stored **literally** as backslash + n. | measured | Python `http.client` raised on the real newline; the escaped form came back as `"a\\nb\\nc"` in both reply and poll |
| 1.10 | What a subscribe/poll returns is **not** byte-identical to what was sent: the cached copy carries `\n` escapes where the reply carried real newlines. A client that reads messages must unescape. | measured | publish reply `"a\nb\nc"`, then `?poll=1&since=all` → `"a\\nb\\nc"` |
| 1.11 | The cache is written asynchronously: a poll fired in the same instant as the publish can return nothing. | measured | immediate poll → empty body; at +0.3 s, +1 s, +3 s, +8 s → the message. Seen twice, two different topics |
| 1.12 | Incremental read works by message id: `?poll=1&since=<id>` returns only messages newer than `<id>`, and an empty body when there are none. | measured | `since=<id1>` → `b''`; after a second publish → exactly that one record |
| 1.13 | Action buttons survive: an `http` action is stored with its URL and method. | measured | `actions` param → reply and poll both list `{"action":"http","label":"Take a photo now","url":…,"method":"GET"}` |
| 1.14 | The action URL runs. A `GET /<topic>/publish?message=…` reaches the target topic and is readable there. | measured | hand-fired the same URL the button carries → `200`, then polled it back |
| 1.15 | `X-Click` / webhook publishing (`GET /<topic>/publish?message=…`) both work. | measured | GET publish returned `200` with a message record |
| 1.16 | Deep link format `ntfy://ntfy.sh/<topic>?display=<name>` subscribes the Android app. | single-source | docs "ntfy:// links" — the app is not on this box, so this row is not measured |

## 2. Limits (this is where free and paid part company)

`GET https://ntfy.sh/v1/tiers` on 2026-11-15 (prices are the server's own numbers, in cents):

| Tier | Price | Messages/day | Attachment max | Attachment total | Cache | Reserved topics |
|---|---|---|---|---|---|---|
| free (per IP) | — | 250 | 2 MiB | 20 MiB | 12 h | **0** |
| Supporter | $6/mo, $60/yr | 2 500 | 25 MiB | 500 MiB | 120 h | 3 |
| Pro | $12/mo, $120/yr | 20 000 | 250 MiB | 2 GiB | 168 h | 10 |
| Business | $25/mo, $240/yr | 50 000 | 1 GiB | 10 GiB | 240 h | 50 |

Also from the same reply: e-mail 5/day, phone calls 0, attachment bandwidth budget 0 (unlimited on
our tier), and `basis: "ip"` for free — the free quota follows the **carrier IP**, i.e. the car's
network, not the car itself.

| # | Fact | Grade | Evidence |
|---|---|---|---|
| 2.1 | Attachment size boundary is exactly 2 097 152 bytes: that many → `200`, one byte more → `413 {"code":41301,"error":"attachment too large, or bandwidth limit reached; increase your limits with a paid plan"}`. | measured | `ntfy_probe.py` size probe |
| 2.2 | Rate-limit and bandwidth error shapes (`429`, `42905`) | single-source | docs "Limitations" / "Replay limits". Not fired deliberately: exceeding them risks a temporary IP ban |
| 2.3 | Account and usage are readable over the API: `GET /v1/account` returns username, role, subscriptions, tokens and remaining quota. | measured | see §3 |

## 3. The throwaway account used for this test

`GET /v1/account` (token redacted here; the token itself lives outside the repo):

```
username=finicky role=user
subscriptions=[zeekrsc_kymoy9m40cuQYXmH]
tokens=[...cvf879 label='zeekr1', ...hsrl0k label=None]
stats={messages:6, messages_remaining:244, reservations:0, reservations_remaining:0,
       attachment_total_size_remaining:20969931}
```

| # | Fact | Grade | Evidence |
|---|---|---|---|
| 3.1 | The account is on the free tier: `reservations: 0`, 2 MiB attachments, 250 messages/day. | measured | `/v1/account` limits block |
| 3.2 | A token gives the app **nothing** on a free public topic: publish works identically without it. | measured | §1.2 |
| 3.3 | Creating a reservation from the app is not reachable through any path we found: `/v1/account/reservation` and neighbours answer `404`; posting there answers `401`. | unknown | four endpoint probes; the ntfy web app reserves through a route we did not reverse |
| 3.4 | What a paid plan unlocks for this feature | measured (limits) / single-source (behaviour) | §2 table; the "nobody else can publish" behaviour is proven only on `announcements` (§1.5), which is a server-side reserved topic |

## 4. What a phone sees — and what we could not measure here

| # | Claim | Grade | Note |
|---|---|---|---|
| 4.1 | The Android app renders an image attachment as a picture in the notification. | single-source | docs screenshot "Image attachment sent from a local file" |
| 4.2 | Attachments are downloaded to the phone automatically **depending on app settings**, and expire server-side after 3 h on the free tier / 24 h Supporter. | single-source | docs "Attachments"; tier table `attachment_expiry_duration` |
| 4.3 | Google-Play flavor uses Firebase; without the app's "instant delivery" toggle, messages can be delayed by doze (minutes to hours). F-Droid flavor has no Firebase and is always instant. | single-source | docs "Instant delivery" |
| 4.4 | The iOS app exists and works the same way at the HTTP level. | single-source | docs "From your phone" |
| 4.5 | End-to-end delivery to a real phone, notification priority, and the deep link opening the right topic. | **unknown** | needs a phone with the app installed. The wizard's "type the code you see" step exists because this cannot be assumed |

## 5. Live topics left behind by this test

Disposable, public, expire from the cache within 12 h. Nothing secret was sent to them; the test
token is not in the topic names.

| Topic | Contents |
|---|---|
| `zeekrsc_kymoy9m40cuQYXmH` | the account's existing subscription; ~9 probe messages and 2 attachments |
| `zeekrsc_demo_ynb4lY2xm7ug` | one realistic alert: title, 3-line caption, 24 699-byte 2×2 JPEG |
| `zeekrsc_Py86PpNcc5L5QXao1z4kpehzzXtiIX2o3Ozqr5ei` + `…8IwIXYW…` | alert with an `http` action button + the command topic it publishes to |
| ~20 `zeekrsc_<random>` topics | single-message probes from the matrix above |
