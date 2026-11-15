#!/usr/bin/env python3
"""Probe ntfy.sh the way the Zeekr Shortcut alert wizard would use it.

Planning tool only - nothing here ships in the APK. It exists so the claims in
docs/ntfy/ can be re-measured instead of remembered.

Standard library only. No token is stored in this file: pass one in NTFY_TOKEN
if you want the account/limits commands.

    python ntfy_probe.py topic                 # what the wizard generates
    python ntfy_probe.py check   <topic>       # cached messages + is it protected
    python ntfy_probe.py send    <topic> [--file f.jpg] [--caption "..."] ...
    python ntfy_probe.py receive <topic> [--since all] [--json-lines]
    python ntfy_probe.py roundtrip             # full wizard flow on a fresh topic
    python ntfy_probe.py limits                # free vs paid tiers, account usage

Server can be overridden with NTFY_SERVER (default https://ntfy.sh).
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
TOKEN = os.environ.get("NTFY_TOKEN", "").strip()

# ntfy allows [-_A-Za-z0-9], up to 64 characters, in the topic name.
ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
PREFIX = "zeekrsc_"
SUFFIX_LEN = 40  # 40 * log2(62) = 238 bits


def gen_topic() -> str:
    """Same shape the wizard should generate: fixed prefix, random tail.

    The tail is the password. 238 bits means a collision with another car on
    the internet is not a risk worth modelling - and nobody can brute-force it.
    """
    tail = "".join(secrets.choice(ALPHABET) for _ in range(SUFFIX_LEN))
    return PREFIX + tail


def unescape(message: str) -> str:
    """Undo ntfy's newline escaping.

    Measured: the publish reply carries real line breaks, but the cached copy
    read back by a poll carries a literal backslash-n instead. Clients unescape;
    so does this, and so must the app when it reads its own command topic.
    """
    return message.replace("\\n", "\n")


def _request(method: str, path: str, body: bytes | None = None,
             headers: dict[str, str] | None = None, timeout: float = 30.0):
    url = SERVER + path
    request = urllib.request.Request(url, data=body, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if TOKEN:
        request.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def _fields(message: str, title: str, priority: int, tags: str) -> dict[str, str]:
    fields = {"message": message}
    if title:
        fields["title"] = title
    if 1 <= priority <= 5:
        fields["priority"] = str(priority)
    if tags:
        fields["tags"] = tags
    return fields


def publish(topic: str, message: str = "", title: str = "", priority: int = 4,
            tags: str = "", filename: str = "", data: bytes | None = None):
    """POST text, or PUT a file with the caption beside it.

    Two measured rules decide the shape of this function (docs/ntfy/FINDINGS.md):

    * An HTTP header value cannot hold a real newline, so a multi-line caption
      next to a picture goes in the *query string* (percent-encoded), not in
      X-Message. A caption sent that way arrives with real line breaks.
    * ntfy's attachment upload is a raw PUT - the body is the file. There is no
      multipart form, so the caption never travels in the body.

    Text-only messages go in the body, where real newlines are legal.
    """
    if data is None and not filename:
        headers = {}
        if title:
            headers["X-Title"] = title
        if 1 <= priority <= 5:
            headers["X-Priority"] = str(priority)
        if tags:
            headers["X-Tags"] = tags
        return _request("POST", f"/{topic}", body=message.encode("utf-8"), headers=headers)

    fields = _fields(message, title, priority, tags)
    fields["filename"] = filename or "photo.jpg"
    headers = {"Content-Type": "image/jpeg"}
    return _request("PUT", f"/{topic}?{urllib.parse.urlencode(fields)}", body=data, headers=headers)


def poll(topic: str, since: str = "all"):
    status, body = _request(
        "GET", f"/{urllib.parse.quote(topic)}/json?poll=1&since={urllib.parse.quote(since)}")
    messages = [json.loads(line) for line in body.decode("utf-8", "replace").splitlines() if line.strip()]
    return status, messages


def cmd_topic(_args) -> int:
    topic = gen_topic()
    print(f"topic      {topic}")
    print(f"length     {len(topic)}  (max 64)")
    print(f"entropy    {round(SUFFIX_LEN * 6.02, 1)} bits")
    print(f"deep link  ntfy://ntfy.sh/{topic}?display=Zeekr")
    print(f"web link   {SERVER}/{topic}")
    return 0


def cmd_check(args) -> int:
    status, messages = poll(args.topic)
    print(f"cached messages  {len(messages)}  (HTTP {status})")
    # A protected (reserved) topic refuses an anonymous publish. A free public
    # topic accepts it - which is exactly the trade the wizard has to disclose.
    status, body = _request("POST", f"/{args.topic}/publish?message=probe",
                            headers={"X-Cache": "no"})
    protected = status == 403
    print(f"anonymous write  HTTP {status} -> {'protected (reserved)' if protected else 'open to anyone who knows the name'}")
    if status not in (200, 403):
        print(f"body             {body[:200]!r}")
    return 0


def cmd_send(args) -> int:
    data = None
    if args.file:
        with open(args.file, "rb") as handle:
            data = handle.read()
        print(f"attachment       {args.file} ({len(data)} bytes)")
    started = time.time()
    status, body = publish(args.topic, message=args.caption, title=args.title,
                           priority=args.priority, tags=args.tags,
                           filename=os.path.basename(args.file) if args.file else "",
                           data=data)
    elapsed = (time.time() - started) * 1000
    print(f"HTTP {status}  {elapsed:.0f} ms")
    print(body.decode("utf-8", "replace")[:600])
    return 0 if status == 200 else 1


def cmd_receive(args) -> int:
    status, messages = poll(args.topic, since=args.since)
    if args.json_lines:
        for message in messages:
            print(json.dumps(message, ensure_ascii=False))
        return 0
    print(f"HTTP {status}  {len(messages)} message(s)")
    for message in messages:
        attachment = message.get("attachment") or {}
        print(f"- id={message.get('id')} prio={message.get('priority')} "
              f"title={message.get('title')!r}")
        print(f"  message={message.get('message')!r}")
        if attachment:
            print(f"  attachment={attachment.get('name')} {attachment.get('size')}B "
                  f"{attachment.get('url')}")
    return 0


def cmd_roundtrip(_args) -> int:
    """The whole free-tier wizard, no account and no token."""
    steps: list[tuple[str, bool, str]] = []
    topic = gen_topic()

    status, messages = poll(topic)
    steps.append(("fresh topic has no cache", status == 200 and not messages, f"HTTP {status}, {len(messages)} cached"))

    caption = "Motion at the car\n2026-11-15 11:46\nSentry: armed"
    started = time.time()
    status, body = publish(topic, message=caption, title="Zeekr alert", priority=4, tags="car,warning")
    publish_ms = (time.time() - started) * 1000
    steps.append(("text alert accepted", status == 200, f"HTTP {status}, {publish_ms:.0f} ms"))

    # The cache writes asynchronously: an immediate poll can come back empty.
    time.sleep(1.5)
    started = time.time()
    status, messages = poll(topic, since="all")
    first_seen_ms = (time.time() - started) * 1000
    seen = [m for m in messages if unescape(m.get("message", "")) == caption]
    steps.append(("caption keeps its line breaks", bool(seen),
                  f"{len(seen)} match, poll {first_seen_ms:.0f} ms"))
    if seen:
        steps.append(("priority survives", seen[0].get("priority") == 4, str(seen[0].get("priority"))))

    payload = os.urandom(180_000)  # about the size of a compressed 2x2 alert photo
    status, body = publish(topic, message=caption, title="Zeekr alert",
                           filename="surround.jpg", data=payload)
    record = json.loads(body) if status == 200 else {}
    url = (record.get("attachment") or {}).get("url", "")
    steps.append(("photo attachment accepted", status == 200 and bool(url), f"HTTP {status}"))
    steps.append(("caption survives beside a photo",
                  unescape(record.get("message", "")) == caption,
                  repr(record.get("message"))))
    if url:
        status, fetched = _request("GET", url[len(SERVER):])
        steps.append(("attachment downloads back",
                      status == 200 and len(fetched) == len(payload),
                      f"HTTP {status}, {len(fetched)} bytes"))

    # Not part of the happy path: the app must not send its own alerts back to
    # itself when it polls a command topic.
    status, messages = poll(topic, since="latest")
    owned = [m for m in messages if m.get("topic") == topic]
    steps.append(("poll can be scoped by since=", status == 200 and len(owned) <= 1,
                  f"HTTP {status}, {len(owned)} message(s)"))

    print(f"topic {topic}\n")
    width = max(len(name) for name, _, _ in steps)
    for name, ok, detail in steps:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {detail}")
    failures = [name for name, ok, _ in steps if not ok]
    print(f"\n{len(steps) - len(failures)}/{len(steps)} passed")
    return 1 if failures else 0


def cmd_limits(_args) -> int:
    status, body = _request("GET", "/v1/tiers")
    tiers = json.loads(body) if status == 200 else []
    print(f"tiers HTTP {status}")
    for tier in tiers:
        limits = tier.get("limits", {})
        name = tier.get("name") or f"free ({limits.get('basis')})"
        prices = tier.get("prices") or {}
        money = f"${prices.get('month', 0) / 100:.0f}/mo" if prices else "-"
        print(f"  {name:<10} {money:<8} msgs={limits.get('messages')} "
              f"file={limits.get('attachment_file_size', 0) // 1024 // 1024}MB "
              f"reservations={limits.get('reservations')} "
              f"cache={limits.get('messages_expiry_duration', 0) // 3600}h")
    if not TOKEN:
        print("\n(no NTFY_TOKEN set: skipping account usage)")
        return 0
    status, body = _request("GET", "/v1/account")
    if status != 200:
        print(f"\naccount HTTP {status} {body[:200]!r}")
        return 1
    account = json.loads(body)
    print(f"\naccount HTTP {status} user={account.get('username')} role={account.get('role')}")
    print(f"  subscriptions {[s.get('topic') for s in account.get('subscriptions') or []]}")
    for token in account.get("tokens") or []:
        print(f"  token ...{token.get('token', '')[-6:]} label={token.get('label')!r}")
    print(f"  stats {json.dumps(account.get('stats'), ensure_ascii=False)}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("topic").set_defaults(func=cmd_topic)

    check = sub.add_parser("check")
    check.add_argument("topic")
    check.set_defaults(func=cmd_check)

    send = sub.add_parser("send")
    send.add_argument("topic")
    send.add_argument("--caption", default="test")
    send.add_argument("--title", default="Zeekr alert")
    send.add_argument("--priority", type=int, default=4)
    send.add_argument("--tags", default="car")
    send.add_argument("--file", default="")
    send.set_defaults(func=cmd_send)

    receive = sub.add_parser("receive")
    receive.add_argument("topic")
    receive.add_argument("--since", default="all")
    receive.add_argument("--json-lines", action="store_true")
    receive.set_defaults(func=cmd_receive)

    sub.add_parser("roundtrip").set_defaults(func=cmd_roundtrip)
    sub.add_parser("limits").set_defaults(func=cmd_limits)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
