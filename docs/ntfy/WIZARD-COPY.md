# ntfy wizard — the words on the screen

The free path must be usable by someone who has never heard of ntfy. Rules that follow from that:

- No "topic", no "token", no "API", no "server", no "ntfy.sh" on the free path. The reader does not
  need to know how it works to make it work.
- One idea per screen, one button per screen.
- Every failure says what to do next, not what went wrong internally.
- The paid path may say "account" and "access token" — a person with an ntfy.sh account already knows
  those words.

Key names follow the existing convention in `res/values*/strings.xml`. Chinese is the default
locale (`values/`), English is `values-en/`, Malay is `values-ms/`.

## Screen 1 — where alerts go

| key | zh | en | ms |
|---|---|---|---|
| `set_alerts_channel_title` | 警报发到哪里 | Where alerts go | Ke mana amaran dihantar |
| `opt_alerts_channel_telegram` | Telegram | Telegram | Telegram |
| `opt_alerts_channel_ntfy_free` | ntfy（免费，不用注册） | ntfy (free, no sign-up) | ntfy (percuma, tanpa daftar) |
| `opt_alerts_channel_ntfy_paid` | ntfy（我有账号） | ntfy (I have an account) | ntfy (Saya ada akaun) |
| `set_alerts_channel_why` | 两个都能把照片发到手机。ntfy 不用注册账号。 | Both send the pictures to your phone. ntfy needs no account. | Kedua-duanya menghantar gambar ke telefon anda. ntfy tidak perlu akaun. |

## Screen 2 — free path: put it on your phone

| key | zh | en | ms |
|---|---|---|---|
| `set_alerts_ntfy_install_title` | 在手机上装一个 app | Put an app on your phone | Pasang satu app di telefon |
| `set_alerts_ntfy_install_step1` | 1. 在手机上装「ntfy」：应用商店搜 ntfy，或扫上面的码 | 1. Install "ntfy" on your phone: search your app store, or scan the code above | 1. Pasang "ntfy" di telefon: cari di gedung app, atau imbas kod di atas |
| `set_alerts_ntfy_install_step2` | 2. 用手机里的相机扫这个二维码 | 2. Point your phone camera at this code | 2. Halakan kamera telefon pada kod ini |
| `set_alerts_ntfy_install_step3` | 3. 装好之后告诉我，我发一条试试 | 3. Tell me when it is installed, and I will send one | 3. Beritahu saya bila sudah siap, saya hantar satu |
| `action_alerts_ntfy_installed` | 装好了 | It is installed | Sudah dipasang |
| `set_alerts_ntfy_qr_failed` | 这个二维码画不出来。改用这条链接：%1$s | Could not draw the code. Use this link instead: %1$s | Kod tidak dapat dilukis. Guna pautan ini: %1$s |
| `action_alerts_ntfy_copy_link` | 复制链接 | Copy link | Salin pautan |

The QR content is `ntfy://ntfy.sh/<topic>?display=Zeekr`. The link shown as a fallback is the same
string. Neither is explained — you either scan it or you don't.

## Screen 3 — did it arrive?

| key | zh | en | ms |
|---|---|---|---|
| `set_alerts_ntfy_code_title` | 收到一条通知了吗 | Did a notification arrive? | Adakah notifikasi sampai? |
| `set_alerts_ntfy_code_body` | 我刚发了一条，里面有四个数字。填进来，我就知道路通了。 | I just sent one. It has four numbers in it. Type them here and I know the path works. | Saya baru hantar satu. Ada empat nombor di dalamnya. Taip di sini supaya saya tahu ia berjaya. |
| `set_alerts_ntfy_code_hint` | 四个数字 | Four numbers | Empat nombor |
| `set_alerts_ntfy_code_ok` | 通了。以后车有动静就发到这里。 | That is it. Alerts will come here from now on. | Berjaya. Amaran akan datang ke sini mulai sekarang. |
| `set_alerts_ntfy_code_wrong` | 这四个数字对不上。通知里还有别的数字吗？ | Those four numbers do not match. Is there another set of numbers in the notification? | Empat nombor itu tidak sepadan. Ada nombor lain dalam notifikasi itu? |
| `set_alerts_ntfy_code_none` | 没收到？先在手机的 ntfy app 里看一眼有没有这个话题，再点「重发一条」。 | Nothing? Open the ntfy app on your phone, check the topic is there, then tap "Send again". | Tiada? Buka app ntfy di telefon, pastikan topik itu ada, kemudian tekan "Hantar lagi". |
| `action_alerts_ntfy_resend` | 重发一条 | Send again | Hantar lagi |

The 4-digit code is the only verification the protocol offers: ntfy has no read receipt
(`FINDINGS.md` §4.5). A wrong code is a real signal — usually the phone is on a different topic
because the QR was scanned twice, or the app was never opened.

## Screen 4 — paid path

| key | zh | en | ms |
|---|---|---|---|
| `set_alerts_ntfy_token_title` | 账号令牌 | Access token | Token akses |
| `set_alerts_ntfy_token_body` | 在 ntfy.sh 网页版「Account → Access tokens」建一个，粘在这里。只存在这台车上。 | Make one on ntfy.sh under "Account → Access tokens", paste it here. It stays on this car only. | Buat di ntfy.sh bawah "Account → Access tokens", tampal di sini. Ia kekal di kereta ini sahaja. |
| `set_alerts_ntfy_token_ok` | 认出来了：%1$s 套餐，可以保护 %2$d 个话题。 | Recognised: %1$s plan, %2$d protected topics available. | Dikenali: pelan %1$s, %2$d topik terlindung tersedia. |
| `set_alerts_ntfy_token_bad` | 这个令牌不对，或者已经失效了。 | That token is wrong, or it has expired. | Token itu salah, atau sudah tamat. |
| `set_alerts_ntfy_no_reservations` | 你这个套餐不能保护话题。要保护，得升级到 Supporter。不升级也能用，只是话题名被人知道了，别人也能发。 | Your plan cannot protect a topic. Protecting one needs Supporter. It works without it — it is just that anyone who learns the name can also post. | Pelan anda tidak boleh melindungi topik. Perlukan Supporter. Tanpa itu pun boleh — cuma sesiapa yang tahu nama itu boleh menghantar. |
| `set_alerts_ntfy_protect_now` | 去保护这个话题 | Protect this topic | Lindungi topik ini |
| `set_alerts_ntfy_protect_done` | 保护好了：别人发不进来了。 | Protected. Nobody else can post here now. | Terlindung. Orang lain tidak boleh hantar lagi. |
| `set_alerts_ntfy_protect_failed` | 还没保护上。网页上确认一下话题名，再点一次。 | Not protected yet. Check the topic name on the website, then tap again. | Belum terlindung. Semak nama topik di laman web, kemudian tekan lagi. |
| `set_alerts_ntfy_server_title` | 服务器地址 | Server address | Alamat pelayan |
| `set_alerts_ntfy_server_summary` | 自己搭的才改，别人保持 https://ntfy.sh | Only change this for your own server; otherwise https://ntfy.sh | Ubah hanya untuk pelayan sendiri; selain itu https://ntfy.sh |

Protection is verified by an anonymous publish probe: `403` means reserved, `200` means still open
(`FINDINGS.md` §1.5). The wizard never claims protection it has not observed.

## Free path — the one warning that must be shown once

| key | zh | en | ms |
|---|---|---|---|
| `set_alerts_ntfy_public_warning` | 免费的话题名就是钥匙：知道这个名字的人也能看到照片、也能发假警报。名字是随机长串，猜不出来。要彻底堵住，用「我有账号」那条路。 | On the free plan the topic name is the key: anyone who learns it can see the photos and post fake alerts. The name is a long random string, so it cannot be guessed. To close that, use "I have an account". | Pada pelan percuma, nama topik ialah kunci: sesiapa yang tahu nama itu boleh lihat gambar dan hantar amaran palsu. Nama itu rawak dan panjang, jadi tidak boleh diteka. Untuk menutupnya, guna "Saya ada akaun". |

## Status messages, mapped from HTTP codes

`NtfySender` converts every response into one of these before the UI sees it.

| HTTP / code | key | zh | en |
|---|---|---|---|
| `403` `40301` | `set_alerts_ntfy_forbidden` | 这个话题不让发。名字或令牌可能不对。 | This topic refuses posts. The name or the token may be wrong. |
| `404` | `set_alerts_ntfy_notfound` | 找不到这个服务器或话题。 | That server or topic was not found. |
| `413` `41301` | `set_alerts_ntfy_too_big` | 这张照片太大，这个套餐发不了。 | This photo is too large for this plan. |
| `429` | `set_alerts_ntfy_rate` | 发得太勤了，等一下再试。 | Sending too often. Wait a moment. |
| network | `set_alerts_ntfy_offline` | 联不上。车这边网不好就等下一次。 | Could not connect. If the car has no signal, the next alert will try again. |

## What must never appear in a string

- the ntfy token, even partly, in any message (the settings row shows the last 4 characters on
  purpose; nothing else may)
- "telegram" on the ntfy path and "ntfy" on the Telegram path, except in the chooser itself
- the words "topic", "bearer", "API", "callback", "webhook" on the free path
