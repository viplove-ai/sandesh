# Sandesh — the Nirman site messenger

A messenger for the people already on a Nirman site. Same credentials, same projects, same
sites, same faces. It carries text, photographs and documents between them, it notifies them
when something arrives, and it keeps a record only of the part that is company business.

This document is the plan: what gets built, in what order, and — where a decision costs
something — what it costs and why it was still the right one.

---

## 1. Scope

### In, for v1

| | |
|---|---|
| **Who** | Every active Nirman user. No separate onboarding, no separate password. |
| **Where** | A conversation per site, a conversation per project, an org-wide announcements channel, and 1:1 with anyone in the organisation. See §19. |
| **What** | Text. Images. Documents (PDF, Office, ≤ 25 MB). |
| **Delivery** | Sent, delivered, read — as live signals, never as stored state. |
| **Notification** | Web Push on a phone that is asleep, in-app on a phone that is awake. |
| **Retention** | Tiered — see §15. Work channels retained; direct messages not. |

### Explicitly out, and staying out until v1 ships

Voice notes, video, calls, screen share, location, reactions, replies-to-message, edit,
forwarding, broadcast lists, message search across conversations, desktop client, guest or
external users, WhatsApp/SMS fallback, chatbots, message translation.

Each of these is a reasonable thing to want. None of them is why this app exists, and
every one of them is a month that the notification work does not get.

---

## 2. The decision everything else hangs off

> *"I do not want to persist any chat of these users. It will be a direct chat."*

That instruction can be honoured three ways, and only one of them survives a construction
site.

### Option A — true peer-to-peer (WebRTC data channel)

Nothing touches a server but the handshake. It is the purest reading of the instruction and
it does not work here. It requires both phones to be awake, online and reachable at the same
instant. A site supervisor at Kausani is on 2G behind carrier-grade NAT; peer connections
there fail to establish more often than they succeed, and the fix — a TURN relay — is a
server that carries every byte anyway. Worse: **a message to someone whose phone is off is
never delivered at all.** Not delayed. Gone. For a site messenger that is a defect, not a
tradeoff.

### Option B — relay with no buffer at all

A WebSocket server that forwards a frame if the recipient is connected and drops it
otherwise. Same fatal property as A, minus the NAT problem. An engineer who reads his phone
at 8 p.m. gets nothing that was sent at 2 p.m.

### Option C — a transient spool, deleted on delivery ← **recommended**

The server holds an undelivered message and nothing else. The moment the recipient's device
acknowledges receipt, the row is deleted. Anything nobody collects is destroyed by a TTL
after seven days. There is no conversation table, no message history table, no archive, no
admin screen that can read a chat, and no backup that contains one.

**What this buys you**

- A message sent to a phone that is off arrives when it comes on. This is the whole
  difference between a messenger and a toy.
- The server is genuinely storage-free in the sense that matters: there is no *history*
  anywhere on it. A subpoena, a disgruntled admin, or a stolen database dump yields at most
  the handful of messages currently in flight.
- The spool is one Postgres table that is deleted from on ack, swept nightly by age. Deletion
  is a job, but it is a job over a durable store — see §18 for why that beat Redis here.

**What it costs, stated plainly**

- **There is no audit trail.** If a supervisor says "I sent you that photograph of the
  cracked beam on Tuesday" and the engineer says he never got it, nothing on the server can
  settle it. For a CPWD contractor that is a real exposure on a disputed instruction. If you
  ever need that, it has to be a deliberate, per-organisation *compliance mode* that says so
  in the UI — not something quietly switched on later.
- **A lost phone is a lost history.** See §8; this is the sharpest edge of the design and it
  has to be told to users on day one, not discovered by them in month four.

**Decision: Option C — for direct messages.** Amended in §15: group channels carrying work
are retained on purpose, and the transient design survives only where it belongs. Read §15
before implementing anything in this section.

### On end-to-end encryption

Not in v1, and the reason is not laziness. E2EE means per-device key pairs, a key exchange,
a "safety number changed" flow nobody on a site will understand, no multi-device without a
sync protocol, and no recovery when a phone is lost — the last of which is already the
weakest point of Option C and E2EE makes it strictly worse.

What v1 does instead: **the spool payload is opaque to the spool.** The envelope carries a
sealed blob and a content type; the spool routes it and never inspects it. Media objects are
encrypted with a per-object AES-GCM key that lives *in the envelope*, not in the object
store — so when the envelope expires the object becomes unreadable even if the bucket
lifecycle rule lags behind. Today the server holds the key. The day you want E2EE, the only
change is who generates that key. Design for it; don't build it yet.

---

## 3. Architecture

```
  ┌──────────────────────┐         ┌──────────────────────┐
  │  Nirman PWA          │         │  Sandesh PWA         │
  │  nirman.example      │         │  chat.nirman.example │
  └──────────┬───────────┘         └──────────┬───────────┘
             │ REST                           │ REST (auth only) + WSS
             │                                │
  ┌──────────▼───────────┐         ┌──────────▼───────────┐
  │  nirman-backend      │         │  sandesh-backend     │
  │  Spring Boot 3.3     │         │  Spring Boot 3.3     │
  │  the system of record│         │  stateless relay     │
  └──────────┬───────────┘         └────┬─────────┬───────┘
             │                          │         │
      ┌──────▼──────┐            ┌──────▼───┐  ┌──▼──────────┐
      │ Postgres    │◄───────────┤ Postgres │  │ MinIO       │
      │ nirman      │  read-only │ sandesh  │  │ chat-media  │
      │ 2 views only│  role      │ outbox   │  │ same server │
      └─────────────┘            └──────────┘  └─────────────┘
```

### Why a second service and not a Nirman module

1. **Lifecycle.** Nirman restarts for a Flyway migration. A WebSocket service that restarts
   with it drops every open connection in the field. These two things must be deployable
   independently or one of them will never be deployed.
2. **Shape.** Nirman is request/response CRUD with a fat transaction per write. Sandesh is
   thousands of idle long-lived sockets. They tune, scale and fail differently.
3. **Blast radius.** A messenger bug must not be able to take down attendance and stock.

### Why not a separate database for identity

The chat service reads **contract views** in the Nirman database through a Postgres role
that can do nothing else. Not the tables — views, created by a Nirman migration, that form
an explicit published interface:

```sql
-- V43__chat_contract_views.sql  (in nirman/backend, not here)
CREATE VIEW chat_directory_v AS
  SELECT u.id AS user_id, u.org_id, u.full_name, u.username, u.session_epoch, u.is_active
    FROM users u;

CREATE VIEW chat_site_membership_v AS
  SELECT a.user_id, a.site_id, s.project_id, s.org_id, s.name AS site_name, p.name AS project_name
    FROM user_site_assignments a
    JOIN sites s   ON s.id = a.site_id AND s.deleted_at IS NULL
    JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL
   WHERE a.assigned_from <= CURRENT_DATE
     AND (a.assigned_to IS NULL OR a.assigned_to >= CURRENT_DATE);

CREATE ROLE chat_reader LOGIN PASSWORD :'chat_reader_password';
GRANT SELECT ON chat_directory_v, chat_site_membership_v TO chat_reader;
```

The alternative — an internal HTTP API on Nirman — was rejected because it makes Nirman's
uptime a hard dependency of chat's, and because the roster is read on every socket connect.
A view is a contract that Nirman's own tests can hold; refactoring `users` behind it breaks
nothing here.

The chat service's **own** Postgres holds three tables and no messages: push subscriptions,
device registrations, per-user notification settings. See §11.

### Stack

Deliberately the same as Nirman, so one engineer can move between them without a context switch.

| Layer | Choice | Note |
|---|---|---|
| Backend | Spring Boot 3.3, Java 21 | Same parent POM version, same `BusinessException`/RFC-7807 error contract |
| Transport | Spring WebSocket, raw JSON frames | **Not STOMP.** Twelve frame types do not need a broker protocol |
| Spool | **Postgres** — Nirman's database, own schema | No Redis. See §18. Durable across restart, which Redis was not |
| Presence / member cache | In-JVM (Caffeine + a map) | Ephemeral by nature; a heartbeat is not worth a write |
| Media | MinIO, bucket `chat-media`, 7-day lifecycle | Same server Nirman already runs |
| Frontend | React 18 + Vite + MUI 6 + Dexie | Same versions as Nirman; theme copied, see §10 |
| Push | Web Push / VAPID (`nl.martijndwars:web-push`) | See §9 |

---

## 4. Identity: reused, not rebuilt

Sandesh has **no user table, no password, no registration, no password reset.** The login
screen posts to Nirman's existing `POST /api/v1/auth/login` and gets back the same token pair
a Nirman phone gets.

**Change required in Nirman: one config line** — add `https://chat.nirman.example` to
`app.cors.allowed-origins`. `SecurityConfig` already reads that from config and already sets
`allowCredentials(true)`.

### Why a separate subdomain and a second login screen

Hosting the chat at `nirman.example/chat` would share `localStorage` and remove the second
login. It would also put two service workers in one origin fighting over scope, and Nirman's
worker at `/` would claim `/chat` navigations. That is a class of bug that presents as "the
app randomly shows the old version" and takes a week to find. A subdomain costs one extra
sign-in and buys a clean scope.

### Validating the token in the chat service

Copy Nirman's `security/` package essentially verbatim — `JwtTokenService`, `AuthenticatedUser`,
`JwtProperties`. Same HMAC secret, same issuer, same claim names (`org`, `roles`, `perms`,
`sites`, `sep`). Signature verification is local and needs no network.

Two claims still cannot be trusted on their own, exactly as in Nirman:

- **`sep` (session epoch).** Nirman increments `users.session_epoch` to sign a person out of
  every device at once. The chat service must honour that or a password reset leaves a live
  socket open.
- **`sites`.** It can say *no* for free. A *yes* is re-read from `chat_site_membership_v`.

But a socket is not a request, and re-querying per frame is absurd. The rule:

> **Authorisation is a snapshot taken at connect, refreshed on `reauth`, and re-taken every
> 120 seconds by a background sweep. A revoked assignment closes membership within two
> minutes; a revoked session closes the socket within two minutes.**

Two minutes is the honest number and it is deliberately written down rather than left as an
implementation detail. Nirman's guarantee is "revoked at 10:00 does not survive a token
issued at 09:58"; ours is two minutes, because the alternative is a database round trip per
typed character.

### Tokens expire; sockets do not

The access token lives fifteen minutes. A socket lives for hours.

```
client                                        server
  │── hello { accessToken } ────────────────────►│  verify sig, sep, roster
  │◄──────────────── ready { userId, convs } ────│
  │                    …                         │
  │  (t+13min: client refreshes via Nirman)      │
  │── reauth { accessToken } ───────────────────►│  re-verify, re-snapshot
  │◄──────────────────────── reauthed ───────────│
```

The token goes in the **first frame, never in the query string.** Nirman already sets
`Referrer-Policy: no-referrer` specifically because a credential in a URL travels; a token in
a WebSocket URL lands in every proxy access log between the phone and the server. If no valid
`reauth` arrives within 60 seconds of expiry, the server closes with `4001 token expired` and
the client reconnects.

---

## 5. Conversations, derived rather than stored

There is no `conversations` table and no `conversation_members` table. Membership is a
question asked of `chat_site_membership_v`, and the answer is cached for 120 seconds.

| Conversation | Id | Members |
|---|---|---|
| Site | `site:<siteUuid>` | Everyone with a live assignment to that site |
| Project | `proj:<projectUuid>` | Everyone with a live assignment to any site in it |
| Direct | `dm:<userA>:<userB>` (uuids sorted) | The two of them, anywhere in the org — **amended in §19** |
| Announcements | `org:<orgUuid>` | Every active user. Admins post, everyone reads — §19 |

This falls straight out of Nirman's model and it is the reason the app needs almost no
administration. Naming a supervisor on a site in Nirman already opens a
`user_site_assignments` row (`SiteStaffingService.updateSiteAccess`). That row is what puts
him in the site's chat. Closing the assignment takes him out of it. **Nobody manages chat
groups, ever.**

Three consequences worth stating because they are features, not bugs:

- Someone added to a site today sees **nothing** that was said before today. There is no
  history to backfill and no leak to worry about.
- Someone removed today keeps what is already on his phone. That is unavoidable in any
  design where the device holds the copy, and pretending otherwise would be a lie in the UI.
- A user with the `ALL` sites claim — an admin, an accountant — can *reach* every site but is
  **not** a member of every site conversation. Otherwise the two admins would be in four
  hundred groups. They get an org directory search for 1:1 instead, and are added to a site
  conversation only by holding a real assignment. **See §19: the original draft promised that
  directory search and then wrote a DM rule that forbade it.**

---

## 6. The message pipeline

### Frame protocol

Twelve frames, JSON, `{ "t": "<type>", ... }`.

| ↑ client → server | ↓ server → client |
|---|---|
| `hello` `{accessToken}` | `ready` `{userId, conversations[], serverTime}` |
| `reauth` `{accessToken}` | `reauthed` / `close 4001` |
| `send` `{clientMsgId, convId, kind, body?, media?}` | `sent` `{clientMsgId, msgId, at}` |
| `ack` `{msgId}` | `deliver` `{msgId, convId, from, kind, body?, media?, at}` |
| `read` `{convId, upToMsgId}` | `receipt` `{msgId, userId, state}` |
| `typing` `{convId}` | `typing` `{convId, userId}` |
| `ping` | `pong` |

### Send path

1. Client mints `clientMsgId = crypto.randomUUID()` — the same client-generated-id
   convention Nirman uses for its offline queue, and for the same reason: the frame can be
   re-sent three times over a bad link without producing three messages.
2. Client writes the message to its own Dexie store as `pending` and renders it immediately.
3. Server verifies the sender is a live member of `convId`, stamps `msgId` and `at`, and
   returns `sent`. The client flips `pending → sent`.
4. **Fan-out on write.** The server resolves the member list *now* and pushes one envelope
   per recipient into the `outbox` table. A recipient who joins the
   site tomorrow was not on the list and gets nothing.
5. For each recipient with a live socket on this instance, deliver immediately. For
   recipients on another instance — if you ever run two — publish over Postgres `LISTEN/NOTIFY`.
6. For each recipient with **no** live socket, enqueue a push notification (§9).

### Delivery and deletion

```
deliver ──► client ──► ack {msgId} ──► LREM spool:{userId} ──► gone
```

The `ack` is sent **after** the client has committed the message to IndexedDB, not on
receipt of the frame. Acking on receipt would delete the server's only copy while the phone
is still writing it, and a browser killed in that window loses the message permanently.
Ack-after-commit means a crash costs a redelivery, which is free — recipients dedupe on
`msgId`.

Anything unacked after seven days is destroyed by the TTL and the sender is shown a
`not delivered` mark. No retry, no dead-letter queue, no operator screen.

### Ordering

Server timestamp, tiebroken by `msgId`. There is no per-conversation sequence counter
because there is no per-conversation state to hold one. Two messages a millisecond apart may
land in different orders on two phones; nobody has ever noticed this in a site conversation
and paying for a counter per conversation to fix it is not worth it.

### Receipts and typing are ephemeral by construction

They are relayed to live sockets and never spooled. A read receipt for a phone that is off
is not information anybody wants two hours later.

---

## 7. Media

```
client                     sandesh                    MinIO
  │── POST /media/upload-url {size, type} ──►│
  │◄──── {mediaId, putUrl, key(AES-GCM)} ────│         (presigned PUT, 10 min)
  │─ encrypt(blob, key) ─ PUT putUrl ──────────────────────►│
  │── send {kind:"image", media:{mediaId, key, name, size, w, h}} ─►│
                                             │  fan-out envelope (contains the key)
recipient ◄── deliver ─────────────────────  │
  │── GET /media/{mediaId}/url ─────────────►│         (presigned GET, 10 min)
  │◄─────────────────────── getUrl ──────────│
  │── GET getUrl ──────────────────────────────────────────►│
  │  decrypt with key from envelope, write to device
```

- **Images are compressed on the device before upload.** Reuse
  `nirman/frontend/src/offline/uploads.ts` almost unchanged — 1600 px long edge, 700 KB
  ceiling, JPEG out. The reasoning in that file (a supervisor on 2G uploads what the device
  sends) applies here word for word.
- **Documents pass through untouched**, 25 MB cap, allow-list of content types, magic-byte
  sniff on the server rather than trusting the declared type.
- **The bucket has a 7-day lifecycle rule.** The object is deleted when every recipient has
  acked, and by the rule if they never do. Because the decryption key lives only in the
  envelope, an object whose envelope has expired is already unreadable — the lifecycle rule
  is housekeeping, not the security boundary.
- Serve every download with `Content-Disposition: attachment` and `X-Content-Type-Options:
  nosniff`. A site engineer will be sent a `.pdf` that is an HTML file eventually.

---

## 8. The device is the archive — and that is fragile

This is the sharpest edge in the whole design and it deserves its own section rather than a
footnote.

Messages and files live in **Dexie / IndexedDB** on each device. That is the only copy that
exists after delivery. Two facts about browser storage that will otherwise be discovered the
hard way:

- **Browsers evict IndexedDB under storage pressure.** Silently. The mitigation is
  `navigator.storage.persist()`, which Chrome on Android grants readily *to an installed
  PWA* and is reluctant to grant to a tab.
- **WebKit evicts non-installed sites after seven days of non-use.** An installed
  home-screen PWA is exempt. A Safari tab is not.

**Therefore: installing the app is not a suggestion, it is a requirement.** First run shows an
install screen, then requests persistent storage, then requests notification permission — in
that order, because on iOS notification permission is only grantable from inside an installed
PWA anyway. The app should refuse to be used as a browser tab beyond a "please install me"
screen. Nirman already ships an `InstallPrompt.tsx`; this is that component with the polite
optionality removed.

Alongside that, three honest affordances:

1. **"Save to phone"** on every image and document — writes to the real Downloads/Photos
   folder, outside the app's storage, where nothing evicts it.
2. **Export conversation** — a `.zip` of a readable transcript plus the files. This is the
   only backup that will ever exist.
3. **A settings screen that says, in plain words: this phone is the only copy. If you lose
   it or clear the app's data, these conversations are gone.** Say it before it happens.

A device-side retention cap (default: keep 90 days / 500 MB, oldest first) keeps the store
from growing without bound on a phone with 16 GB.

---

## 9. Notifications — the thing Nirman is missing

This is the reason to build the app in this order. Nirman today has no notification
mechanism of any kind — no push, no VAPID keys, no service-worker push handler; grep across
`backend/src` and `frontend/src` finds nothing. Sandesh needs one, so **build it as a
standalone module with a generic interface and let Nirman use it next.**

```java
public interface Notifier {
    /** @param tag collapses an earlier notification with the same tag */
    void notify(UUID userId, Notification notification);

    record Notification(String title, String body, String deepLink, String tag, int badge) {}
}
```

Sandesh calls it with a chat message. Six months later Nirman calls it with "your expense was
approved" and "the DPR for Kausani needs verifying" — and none of that work has to be done
twice.

### v1: Web Push (VAPID)

Standard, no vendor SDK, works on Chrome/Android and Safari/iOS 16.4+.

```
sandesh ─(encrypted payload, VAPID JWT)─► fcm.googleapis.com / web.push.apple.com
                                            └─► browser wakes the service worker
                                                  └─► self.registration.showNotification(...)
```

**Payload contents.** The payload is encrypted to the browser's own key, so Google and Apple
carry it without being able to read it. Therefore the notification can safely carry a preview:

```json
{ "convId": "site:…", "convName": "Kausani Block B", "from": "R. Negi",
  "kind": "image", "preview": "sent a photograph", "msgId": "…", "badge": 4 }
```

with a per-user setting to suppress previews to just `New message`.

### Four constraints that will bite if they are not designed for now

1. **iOS forbids silent push.** Every push delivered to an iOS PWA *must* result in a visible
   notification; violate it a few times and the subscription is revoked. This kills the
   otherwise-attractive "silent ping, then fetch the spool" pattern. The design must be
   **payload-first**: the notification renders from the payload alone, and the socket sync is
   a bonus the service worker attempts afterward.
2. **iOS requires an installed PWA and a user gesture.** `Notification.requestPermission()`
   from a Safari tab does nothing. Covered by the install-first flow in §8.
3. **Android OEM battery management.** Xiaomi, Realme, Oppo and Vivo — most of the phones in
   this market — aggressively kill background work. Push generally still arrives because it
   is Chrome that is woken, and Chrome is usually whitelisted. The failure mode is battery
   saver and "restrict background data". Ship a Notification Health screen: a "send me a test
   notification" button and per-OEM instructions for the settings toggle. Do not make support
   diagnose this over the phone.
4. **Subscriptions rot.** Endpoints expire, and the push service answers `404`/`410`. Delete
   the row on those two codes and only those two — deleting on a `503` throws away a working
   subscription during a Google outage.

### When to push

```
recipient has a live socket AND the page is visible   → do not push (in-app toast)
recipient has a live socket AND the page is hidden    → push
recipient has no live socket                          → push
recipient has muted this conversation                 → do not push
now is inside the recipient's quiet hours             → do not push
```

Collapse by `tag = convId`, so twenty messages in the site group produce one notification
that reads *"Kausani Block B · 20 new messages"* rather than twenty rows. Set the badge from
the unacked spool depth via `navigator.setAppBadge`.

Notification actions: **Reply** (inline text on Android) and **Mark read** — both handled in
the service worker without opening the app.

### v2, only if v1 reliability is not good enough

Wrap the same web app in an **Android TWA** via Bubblewrap, publish to Play, and take
notifications through native FCM. Same codebase, same URL, native-grade delivery and a Play
Store presence that field staff find easier to install than "open Chrome and tap the three
dots". Do **not** start here; ship web push first and let real complaint volume decide.

---

## 10. Design: what to copy from Nirman and what not to

Copy verbatim:

- `app/theme.ts` — the palette (warm paper `#F7F3E9`, ink `#14181D`, the five status colours),
  IBM Plex Sans / Mono, `TOUCH_TARGET = 48`, and the Kalam handwriting face for headings only.
- `app/dayAccent.ts` — the weekday accent. It is written as CSS custom properties precisely so
  it costs nothing, and it is the strongest single piece of visual identity the product has.
- `public/brand/*` icon set, re-cut with a distinct badge so the two home-screen icons are
  not confused. Same family, different mark.
- `shared/apiClient.ts` and `shared/session.ts` nearly verbatim — the single-flight refresh
  promise is load-bearing here for exactly the reason its comment gives.

**Do not copy the drawn borders onto message bubbles.** Nirman's irregular
`border-radius: 14px 8px 15px 9px / 9px 15px 8px 14px` and offset shadows read beautifully on
a register card. On two hundred stacked bubbles in a scrolling list they are visual noise and
a measurable paint cost. The ink-and-paper language belongs on the conversation list, the
headers and the send button; the bubbles themselves get a plain 12 px radius on the same
paper ground.

Chat-specific rules worth fixing early:

- Virtualise the message list (`@tanstack/react-virtual`, already a Nirman dependency).
- The composer is pinned and never moves under the keyboard — use `dvh`, not `vh`.
- Outbound bubble = day accent tint; inbound = surface. Never colour a bubble by status.
- One-handed reach: send button bottom-right, attach bottom-left, both ≥ 48 px.

---

## 11. What is actually stored

**In the chat service's own Postgres** — database `sandesh`, on the server Nirman already runs:

```sql
-- the spool. Deleted from on ack; swept nightly by age.
outbox (id, recipient_id, msg_id, conv_id, envelope jsonb, created_at)
  -- ix_outbox_recipient (recipient_id, created_at)   ← the only hot query
  -- DELETE WHERE created_at < now() - interval '7 days'   ← @Scheduled, nightly

push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_ok_at)
devices            (id, user_id, device_label, platform, installed_at, last_seen_at)
notify_settings    (user_id, previews_enabled, quiet_from, quiet_to, muted_conv_ids[])
chat_restrictions  (user_id, org_id, level, reason, restricted_by, restricted_at, until)
chat_audit         (id, actor_id, action, subject, detail jsonb, at)
retained_messages  -- Tier 2 only, §15. Never DMs
```

**In the JVM, and nowhere else** — presence (`Map<userId, Set<sessionId>>`, 90 s heartbeat) and
the 120-second conversation-member cache (Caffeine). Both are ephemeral by nature: a heartbeat
that costs a database write is the one thing at this scale that would actually hurt, and losing
presence on a restart is correct — every socket died with it anyway.

**In MinIO** — `chat-media`, encrypted objects, 7-day lifecycle.

**In the Nirman database** — nothing. Two read-only views, no writes, ever.

---

## 12. Roadmap

Sized for one backend and one frontend engineer working together. Weeks are working weeks
and assume the Nirman stack is already running locally.

### Phase 0 — Foundations · 1 week

Scaffold `chat-messenger/backend` (Spring Boot 3.3, Java 21, same POM shape) and
`chat-messenger/frontend` (Vite + React 18 + MUI 6, theme copied). Docker compose adds a
`sandesh` database on the Postgres container already there — no new service.
Nirman gets `V43__chat_contract_views.sql` and the CORS origin. CI workflow extended with two
jobs mirroring the existing ones.

**Done when** both apps boot, and a login against Nirman's auth API returns a token the chat
backend can verify and turn into a member list.

### Phase 1 — Text, one to one · 2 weeks

WebSocket endpoint and the twelve frames. The `outbox` spool with ack-and-delete. Conversation list
derived from the membership view. Dexie store on the device. Send, deliver, ack, receipts,
typing. Reconnect with backoff. `reauth` before token expiry.

**Done when** two phones on different networks exchange text, one of them is switched off for
an hour and receives everything on waking, and `SELECT count(*) FROM outbox` is zero afterward
— **including after the backend is restarted mid-test**, which is the case Redis would have failed.

### Phase 2 — Groups and media · 2 weeks

Site and project conversations with fan-out on write. Image compression, encrypted upload,
presigned download, decrypt-and-store. Document send with type allow-list and magic-byte
check. "Save to phone". Device retention cap.

**Done when** a supervisor photographs a beam, four people on the site conversation receive
it, and the MinIO object is gone once all four have acked.

### Phase 3 — Notifications · 2 weeks ← *the point of the exercise*

`Notifier` module with the generic interface. VAPID keys, subscription lifecycle with
`404`/`410` pruning, service-worker push and notificationclick handlers, collapse tags, badge
count, quiet hours, per-conversation mute, inline reply action, and the Notification Health
screen with the OEM guidance.

**Done when** a locked Redmi with the screen off shows a collapsed notification within five
seconds, tapping it opens the right conversation, and the health screen's test button works
on iOS, Chrome/Android and one MIUI device.

### Phase 4 — Hardening and pilot · 2 weeks

Install-first gate and persistent-storage request. Export conversation. Rate limiting per
user and per conversation. Load test: 500 concurrent sockets, 50 msg/s. Multi-instance
fan-out proven with two backend replicas. Playwright e2e over the built PWA, matching
Nirman's convention. Then **one real site, ten users, two weeks**, with the notification
health screen watched daily.

### Phase 5 — Retention, pinning and admin controls · 2 weeks

Tier 2 storage with the enforced deletion job. Pin-to-record into the Nirman site record.
Re-sync of retained channels onto a new device, bounded by assignment dates. The
dual-authorised export with its audit entry and in-channel notice. `chat_restrictions` with
mute and block, the report button, and the `chat:restrict` permission migration.

**Gate, not a task:** counsel has signed off the retention period, and the DPDP notice and IT
policy language are live — *before* the first Tier 2 message is written.

### Phase 6 — The action channel · 2 weeks

The Nirman system conversation and its actionable cards — expense approvals first, then DPR
and attendance verification. Nirman gains the post-commit event and dispatcher. Every action
executes against Nirman with the user's own token.

**Done when** an engineer approves an expense from a notification without opening Nirman, a
second tap does not approve it twice, and one already handled elsewhere collapses to "already
approved by".

### Phase 7 — Decided by the pilot, not by this document

Android TWA + FCM if push reliability disappoints. Voice notes if the site asks for them —
they will. Nirman's wider notifications through the same `Notifier`, which by then is proven.

**Total to pilot: ~9 weeks; ~13 to the full picture.** Notifications are Phase 3 and not Phase 5 deliberately: a
messenger nobody is told about is a messenger nobody opens, and finding out on an OEM device
that push does not arrive is the kind of discovery that must happen in week seven, not week
fifteen.

---

## 13. Risks, ranked

| # | Risk | Mitigation |
|---|---|---|
| 1 | **Push does not arrive reliably on Indian OEM Androids.** Single biggest threat to adoption. | Health screen + OEM guidance in Phase 3; TWA/FCM held in reserve as Phase 5 |
| 2 | **Users lose history when a phone dies**, and blame the app. | Say it on the install screen, in settings, and once more the first time a file is saved. Export exists from Phase 4 |
| 3 | **No audit trail on a disputed instruction.** A contractor-specific exposure. | Named as a consequence now, not discovered later. If it matters, it becomes an explicit per-org compliance mode — a product decision, not a patch |
| 4 | IndexedDB evicted despite `persist()` | Install-first gate; retention cap; "Save to phone" for anything that matters |
| 5 | The 120-second authorisation window | Written into the spec. Tightenable to ~10 s with a `LISTEN/NOTIFY` revocation signal from Nirman if it ever matters |
| 6 | Chat's read-only role becomes a coupling to Nirman's schema | Views are the contract; a Nirman test asserts they still resolve |
| 7 | iOS PWA push quietly regresses across Safari releases | Health screen is the canary; one iOS device in the pilot |

---

## 14. Changes required in Nirman

Small, and worth listing exactly because the footprint being this small is the argument for
the whole approach.

1. `backend/src/main/resources/db/migration/V43__chat_contract_views.sql` — the two views and
   the `chat_reader` role. Additive; nothing existing changes.
2. `app.cors.allowed-origins` — append the chat origin. Config only.
3. A test asserting both views resolve and return the expected columns, so a future refactor
   of `users` or `user_site_assignments` fails in Nirman's CI rather than in chat's
   production.

That is the entire list. No Nirman code changes, no new endpoints, no auth changes.

---

## 15. Retention — what the company should keep, and what it should not

*Amends §2. Where the two disagree, this section wins.*

The question is not "should we keep chat." It is **which chat**, because a site group and two
people talking are not the same kind of object and a single policy over both gets one of them
wrong.

### The recommendation

Three tiers, decided by what the conversation *is*, not by who is in it.

| Tier | What | Retention | Who can read it |
|---|---|---|---|
| **1 — System & actionable** | Approvals, DPR verifications, expense sign-offs, task assignments issued through Sandesh | **Permanent.** Already a Nirman record; the chat is only the delivery surface | Whoever could already see the underlying Nirman record. No new access at all |
| **2 — Site & project channels** | The work channels. Instructions, defect photographs, material shortages, "pour is delayed" | **Retained, disclosed, time-boxed.** Default: contract period + defect liability period, or three years, whichever is longer | **Nobody, by default.** Not admins, not HR. Readable only by a named, dual-authorised, logged export against a specific dispute |
| **3 — Direct 1:1 messages** | Two people talking | **Not retained.** Transient spool exactly as §2 describes | Nobody. There is nothing to read |

### Why this split and not "keep everything"

**Because in construction, the record is the argument.** A CPWD job lives or dies on who told
whom to do what and when — extension-of-time claims, defect liability, arbitration. Tier 2 is
the single most valuable data the company will generate and throwing it away on a seven-day
TTL is the expensive mistake, not the safe one. My original §2 got this wrong by treating all
chat as one thing.

**Because keeping the 1:1s buys nothing and costs the product.** This is the part worth being
blunt about: *if Sandesh feels watched, the real conversations move to WhatsApp.* Then the
company has no record, no tool, and its site photographs sitting on personal phones in a
consumer app it does not control. That outcome is strictly worse on every axis — legal,
operational, and security — than not retaining DMs in the first place. **A retention policy
that drives people out of the app is a retention policy that retains nothing.**

**Because "retained" and "browsable" are different decisions, and conflating them is what
poisons adoption.** Tier 2 messages exist in storage. No screen renders them to an
administrator. Access is an export: two named approvers, a stated reason, a bounded date range
and site, written to Nirman's `audit_logs`, and — this matters — **the channel is told it
happened.** People tolerate a record. They do not tolerate an audience they cannot see.

### The feature that makes this work: pin to record

Any member can promote a message or photograph into the Nirman site record — one tap, it
becomes an attachment on that site with the sender, timestamp and original text preserved.

This is the strongest idea in this section. It gets you the evidence that actually matters
(the photograph of the cracked beam, the instruction to proceed) as a **deliberate act by the
person who knows it matters**, rather than by dragnet. Retention is the safety net; pinning is
the mechanism. Build pinning even if you retain nothing.

### What retention changes elsewhere in this plan

- **The lost-phone problem largely goes away for Tier 2.** A new device re-syncs the site
  channels it is entitled to, bounded by the retention window and by the user's assignment
  dates — they get the site's history for the period they were posted there, and not a day
  outside it. §8's fragility now applies only to DMs. This is a real UX win and it arrived as
  a side effect.
- **E2EE is off the table for Tiers 1 and 2**, permanently, not just deferred. You cannot keep
  a record you cannot read. It stays available for Tier 3 if you ever want it.
- **Media lifecycle changes from 7 days to tiered storage.** Tier 2 objects move to
  infrequent-access after 90 days rather than expiring. Budget for it: at ~700 KB a
  photograph and a busy site producing perhaps 30 a day, one site is roughly 8 GB a year.
- **The retention window must be set once and enforced by a job**, not by intention. A window
  nobody deletes against is "permanent" wearing a policy's clothes.

### Before this ships — and this part is not mine to decide

I can build the architecture; the retention period and the consent language need your counsel's
sign-off. Three things to put in front of them:

1. **DPDP Act 2023** — notice, purpose limitation and storage limitation apply to employee
   data. "Keeping it in case it's useful" is not a purpose. The retention window needs a stated
   business reason, which Tier 2 has and Tier 3 does not.
2. **The employment contract and IT policy** must say the work channels are retained, in plain
   language, before the first message is sent. Retention without disclosure is the actual legal
   and cultural risk here — not retention itself.
3. **Whether the arbitration window for your CPWD contracts is longer than three years.** If it
   is, the retention period follows it, not a round number I picked.

### If you disagree and want everything kept

It is your company and your risk. Two conditions I would still hold to, because they cost
nothing and they are what keeps the thing usable: **tell people, in the app, in the channel**;
and **keep the export gate** — retention with open admin browsing is the version that fails.

---

## 16. Sandesh as Nirman's action channel

The natural second life of this app, and the architecture already reaches for it: the
`Notifier` interface in §9 exists so Nirman can call it. This section is what to call it *with*.

### The shape

A per-user **Nirman** system conversation carrying actionable cards:

> **Expense ₹42,600 · Kausani Block B** — submitted by R. Negi, awaiting your L1 approval
> `[ Approve ]  [ Reject ]  [ Open in Nirman ]`

Candidates, in the order they are worth building: expense approvals (L1 and L2), DPR
verification, attendance verification, stock correction requests, advance settlements, and
period-close reminders. Nirman's home screen already carries a *"WAITING ON YOU · 3"* register
— this is that list, pushed rather than pulled.

### The rule that must not be broken

> **The chat service never performs a Nirman action.** It renders a card. The button posts to
> Nirman's own endpoint with the user's own access token, so `@PreAuthorize`, `SiteAccessGuard`
> and `PeriodLockGuard` all run exactly as they do from the Nirman app.

A service account that approves expenses on a user's behalf is an authorisation bypass with a
friendly name, and it would be the single worst thing this project could ship. If the token has
expired, the card refuses and opens the sign-in — it does not fall back to anything.

Three details that follow:

- **Idempotency.** Reuse Nirman's existing `Idempotency-Key` header. A double-tap on a slow
  connection must not approve twice.
- **Stale cards collapse.** Nirman's `@Version` returns 409 when the record moved. Render that
  as *"Already approved by S. Rawat at 14:20"* rather than an error — approvals get handled in
  two places and that is normal, not a fault.
- **Outbound only in v1.** No replying to the system conversation, no chatbot. A text box that
  looks like it works and does not is worse than no text box.

### What it costs Nirman

This is the first change that is not free. Nirman gains an outbound call to Sandesh's
`Notifier` at the points where a record starts waiting on somebody. Keep it **asynchronous and
failure-tolerant** — a notification that cannot be delivered must never roll back the approval
that triggered it. An event published after commit, consumed by a small dispatcher, is the
shape; a synchronous HTTP call inside the transaction is not.

**Sequencing.** Reserve the system-conversation type and the `Notifier` signature in Phase 3.
Build the cards in Phase 6, after the pilot has proven that notifications actually arrive on
the phones your staff carry. Actionable notifications on an unreliable transport are worse than
none — a missed approval is a stalled site.

---

## 17. Admin controls: blocking, muting, and what a block actually does

### Two different things, deliberately separate

| | Where it lives | Effect |
|---|---|---|
| **Deactivate the user** | Nirman — `users.is_active`, `session_epoch` | Already exists. Kills Nirman *and* Sandesh, because `chat_directory_v` carries both columns. **No new work.** |
| **Block from Sandesh only** | The chat service's own Postgres | Still works in Nirman; cannot use the messenger. New table |

The second is what you are asking for, and it belongs in chat's database rather than as a
Nirman view — it is chat-specific state, and putting it in Nirman would mean chat needs write
access there, which is the boundary the whole design is built on keeping.

```sql
chat_restrictions (
  user_id, org_id,
  level,          -- MUTED | BLOCKED
  reason,         -- required; shown to the user
  restricted_by, restricted_at,
  until           -- null = indefinite
)
```

### Two levels, because a block is usually too blunt

- **MUTED (read-only).** Can open conversations and read; cannot send. The right answer for
  someone flooding a site channel, or someone under investigation who still needs to do their
  job. Reversible without ceremony.
- **BLOCKED.** No connection at all.

### What a block does, precisely

1. Every open socket for that user closes with `4003 restricted`.
2. Their spool is purged — messages in flight to them are destroyed, not held.
3. Their push subscriptions are deleted, so no notification arrives afterward.
4. The app shows the **reason**, not a blank failure. A user who cannot find out why they are
   locked out phones a supervisor, who phones you.
5. Enforced at connect *and* in the 120-second sweep, so a live socket dies within two minutes
   — same guarantee as a revoked assignment.
6. Written to a `chat_audit` table with who, when and why. Chat keeps its own audit log rather
   than writing to Nirman's, again to preserve the read-only boundary.

### What a block does not do

**It does not erase anything already on their phone.** Cannot, in any design where the device
holds a copy. Say so in the admin screen at the moment of blocking, so nobody believes a block
is a recall. If the device genuinely must be cleared, that is mobile device management, and it
is a different product.

### Permission and roles

A new `chat:restrict` permission — a seeded row in a Nirman migration, following the existing
convention that a new permission is a migration and never a constant in code. Granted to
`ADMIN` only at first. Note this is the one place chat needs to *read* a permission from the
token rather than derive everything from site membership.

### One thing worth adding that you did not ask for

**A report button.** A member can report a message to the admins, which is what surfaces the
problem that leads to a block. Without it, blocking is a control you only use after somebody
telephones you about it. Cheap to build, and it is the input side of the feature you are asking
for.

Person-to-person blocking — user A refusing DMs from user B — I would leave out of v1. In a
work app with derived membership it mostly duplicates "remove them from the site", and it
creates confusing states where a person is in a channel with somebody they have blocked.
Revisit if the pilot surfaces a real need.

---

## 18. Infrastructure: why there is no Redis

*Amends §3 and §11. Draft 1 specified Redis 7; it is gone.*

Redis was carrying four jobs. Postgres and the JVM take all four, and one of them comes out
better rather than merely equal.

| Job | Was | Is now | Verdict |
|---|---|---|---|
| Undelivered spool | `spool:{userId}` list, 7-day TTL | `outbox` table, deleted on ack, nightly age sweep | **Better** — see below |
| Presence | `presence:{userId}`, 90 s TTL | `Map<userId, Set<session>>` in the JVM | Equal, and free |
| Member cache | `conv:{convId}:members`, 120 s | Caffeine, 120 s | Equal, and free |
| Cross-instance fan-out | pub/sub | Postgres `LISTEN/NOTIFY` — or nothing, on one instance | Equal at this scale |

### The spool is genuinely better in Postgres

This is not a compromise, and it is worth being clear about because the instinct runs the
other way.

**Redis without persistence configured loses the undelivered spool on restart.** For a
messenger that is not a performance characteristic, it is a correctness bug: every message in
flight to a phone that is switched off disappears because the ops team restarted a container.
Getting it back means AOF with `appendfsync everysec`, which is a durable write to disk on a
timer — at which point you are running a second, less familiar database to do what the first
one already does properly, with its own backup story, its own restore drill and its own
monitoring.

The Phase 1 acceptance test now says so explicitly: the backend is **restarted mid-test** and
the message still arrives.

### Presence and the member cache belong in memory, not in a database

Redis's real advantage is high-frequency ephemeral writes, and a heartbeat every ninety
seconds per user is exactly that. But putting them in Postgres was never the alternative —
keeping them **in the JVM** was. Presence is per-instance state about sockets this instance is
holding; it cannot outlive the process it describes, so there is nothing to persist and
nothing to share.

### Fan-out is a problem you do not have yet

The whole cross-instance apparatus assumed more than one backend instance. Idle WebSockets are
cheap — a single JVM handles thousands of them comfortably, and this is a contractor with tens
to low hundreds of staff. **Run one instance.** Keep `MessageBus` as an interface with an
in-process implementation; the day a second instance is genuinely needed, a `LISTEN/NOTIFY`
implementation is a few hours' work behind that seam.

### The database is not in the delivery hot path

Worth stating, because "the spool is a table now" sounds slower than it is. For a recipient who
is **online**, the message goes straight from the send handler to their socket in the same JVM.
The `outbox` row is written for durability, not consulted for delivery. The table is read on
exactly one occasion: a device reconnecting and asking what it missed.

### Where it lives

*Amended at the first production deploy. Draft 1 specified a separate database; it is now a
schema inside Nirman's.*

**Nirman's database, a `sandesh` schema inside it.** One `DataSource`. Flyway creates the schema
and keeps its own history table there, so neither service's migrations can see the other's;
Hibernate resolves unqualified entity tables to `sandesh`, and the two contract views are read
as `public.` explicitly, at every call site.

The original decision was the other one, and its argument was not wrong — it is the price now
being paid, and it should be stated rather than discovered: shared vacuum, shared backups,
shared restore, and a chat table that can bloat the disk Nirman is using.

What overturned it was credential arithmetic rather than the boundary. A second database meant a
second role, a second connection string, and `chat_reader` again for the views: four values to
hold in step across two systems, every one of them a way for the service to fail at boot with an
error naming the wrong thing. What replaces the grant is narrower and honest about being
narrower — this service reads Nirman's data through two views by convention, and
`NirmanDirectoryService` remains the only class permitted to.

The seam is still where §3 put it. If the boundary is ever wanted back, it is the schema that
moves, not the callers.

### What is left to run

Postgres and MinIO. Both are already running for Nirman. **Sandesh adds no new
infrastructure at all** — one schema and one bucket on servers that exist.

---

## 19. The user who belongs to no project

*Amends §5. It fixes a contradiction that was in the original draft, not a new requirement.*

### What happens today, and why it is wrong

**Can they log in?** Yes, and that is correct. Sandesh does not authenticate anybody — Nirman
does. An active user with a valid password gets a token; `chat_directory_v` confirms they exist
and are active; the socket opens.

**Can they chat?** Under §5 as originally written: **no, with nobody, at all.** Every
conversation type required a live `user_site_assignments` row, and they have none. Site channel:
no. Project channel: no. Direct message — "iff they share at least one site" — no, because they
share no site with anyone.

They log in successfully and land on an empty screen with no explanation. That is not a security
outcome, it is a broken one.

### Who this actually hits, and it is not an edge case

| Who | Assignment rows | Under the old rule |
|---|---|---|
| **Accountant** | Never any. The role is *"company-wide financial visibility, payments and advances"* — head office, not a site | Could message **nobody**. The person whose whole job is chasing a bill could not message the supervisor who raised it |
| **Admin** | Usually none. They hold the `ALL` claim instead | Could message nobody — see the contradiction below |
| **New hire** | None until posted | Empty app on day one, which is the day they most need to reach somebody |
| **Between postings** | Rotated off one site, not yet on the next | Drops out of everything mid-employment |
| **Assignment starts tomorrow** | Row exists, `assigned_from` is in the future | Correctly not yet a member. This one is working as intended |

### The contradiction in the original §5

Draft 1 said admins and accountants "get an org directory search for 1:1 instead" — and then
defined the direct conversation as requiring a shared site, which forbids exactly the
conversation the directory search would produce. Two rules, one page apart, cancelling each
other. The directory search was the right instinct; the DM rule was written for field staff and
applied to everybody.

### The fix: separate *who I may talk to* from *which channels I am in*

These were conflated. They are different questions and they get different answers.

**Channel membership stays derived from live site assignments. Unchanged.** That rule is good
and it is what keeps a supervisor's app small and relevant.

**Direct messages get their own rule: anyone in the same organisation.**

```
dm:<a>:<b>  is permitted iff  a.org_id = b.org_id
            and neither party is BLOCKED (§17)
            and both are active in chat_directory_v
```

That is the whole rule. No shared-site test.

**Why org-wide rather than a widened site rule.** This is a contractor with tens to low hundreds
of staff; everyone in the directory is a colleague. The reasons to restrict DMs are to keep the
conversation list small and to avoid exposing a staff roster — and the first is solved better by
*defaults* than by *permissions*, while the second barely applies, since Nirman's own screens
already show these names to most roles.

So: **search is broad, defaults are narrow.**

- The conversation list shows what is derived — your sites, your projects, recent DMs. Small.
- The **directory search** reaches the whole organisation, ranked so people you share a site with
  come first.

A supervisor opens the app and sees his site and five names, exactly as before. An accountant
opens it, searches "Negi", and can ask about the bill.

> **This grants nothing in Nirman.** Sandesh holds no project data. Being able to message someone
> is not being able to see their site, their figures or their records — every one of those is
> still decided by `SiteAccessGuard` on Nirman's side. The only thing org-wide DM exposes is the
> staff directory: names and roles.

If you would rather keep it tight, make it an org setting — `dm_scope = ORG | SITE`, default
`ORG`. One column, and it is there when a client at ten times this size asks for it. Do not build
the setting first.

### The announcements channel — the other half of the answer

Even with org-wide DM, an unassigned user still opens an app with **zero channels**. So add one
every active user belongs to regardless of assignment:

```
org:<orgUuid>   Every active user in the organisation.
                Admins post. Everyone reads. Nobody replies.
```

This is also the answer to *"send notifications or any communications to the team"* from §16 —
the same feature solves both. Holiday notices, policy changes, a site being handed over, the
office being closed on Thursday. It is Tier 2 under §15 (retained: it is company communication
by definition), and its posting right is the `chat:announce` permission, seeded in the same
migration as `chat:restrict`.

### The empty-state screen, which is a design task not a technical one

A user with no channels and no recent DMs must never see a blank list. They see:

> **You are not posted to a site yet.**
> You will see your site's conversation here when you are. Meanwhile you can message anyone at
> \<Org Name\> — and company notices appear in **Announcements**.
> `[ Find someone ]`

Say the reason. A blank screen with no explanation is how a new hire concludes the app is broken
and goes back to WhatsApp on day one, which is precisely the failure §15 is trying to avoid.

### What was considered and rejected

**Refusing the login outright for anyone with no assignment.** Tempting, and wrong: it locks out
the accountant and the admin, who are legitimate permanent users with no site, and it makes a new
hire's first interaction with the company's tool a rejection. Being unassigned is a normal state
in this business, not an anomaly.

**Auto-joining unassigned users to every site channel.** Puts two admins in four hundred
conversations and makes the app useless for them. §5 rejected this already and it stays rejected.

### The other direction: someone whose assignment ends

They drop out of the site channel within 120 seconds, the same guarantee as any other revocation.
Their **device** keeps the copy it already holds — unavoidable, and stated in §8. Under §15 they
do **not** get that history back on a new phone: the Tier 2 re-sync is bounded by their own
assignment dates, so a re-issued handset gives them the period they were actually posted there
and not a day outside it. They keep org-wide DM and Announcements, because they are still
employed.

---

## 20. The administrator

*Completes §5, §15, §17 and §19, which each said something about admins and left the whole
unstated.*

An admin holds the `ALL` sites claim in Nirman and usually holds **no** `user_site_assignments`
rows at all. Under §19 they can now log in, use the directory, DM anyone and read Announcements.
Two questions remain: how do they get *into* a site conversation, and what can they not do.

The second question is the more important one. For a messenger, **what the administrator cannot
do is the product**. An admin who can quietly read everything is a WhatsApp migration with extra
steps.

### The gap: an admin cannot enter a site conversation at all

Channel membership derives from `user_site_assignments`, and the only thing that writes those
rows is Nirman's site staffing screen — which grants them by **naming somebody ENGINEER or
SUPERVISOR of that site**. So today an admin who needs to say something to Kausani Block B has
to enter themselves in the staffing register as its supervisor. That is falsifying an
operational record in order to send a message. Not acceptable.

### The fix: join on demand, and never invisibly

An admin may open any site or project conversation in their own organisation, directly, without
an assignment row. The principle:

> **An administrator may go anywhere, and never silently.**

This is not privilege escalation — they already hold `ALL` sites in Nirman and can read every
figure the site produces. The thing being designed here is not authorisation, it is
**transparency**. So:

1. Joining posts a system line in the channel: *"S. Rawat (Administrator) joined this
   conversation."* Same line everyone else's join produces.
2. They appear in the member list, marked as an administrator.
3. The join is written to `chat_audit`.
4. **They receive the channel from the moment they join, and not one message earlier.** Even
   where Tier 2 retention holds a backlog, joining does not hand it over — otherwise "join"
   quietly becomes "read everything", which is the browse screen §15 refused, wearing a
   different name. The backlog is reachable only through the export gate, like anything else.
5. Leaving posts a line too, and they are not auto-subscribed to push — a person in four hundred
   channels who is notified by all of them will mute the app, and then the one notification that
   mattered is muted with it.

There is no mechanism, anywhere in this design, by which an admin reads a channel without the
channel knowing.

### What an administrator cannot do

The list is the trust statement. It belongs on a screen in the app, not only in this document.

| | Why |
|---|---|
| **Read a direct message** | Not "not permitted" — **there is nothing to read.** DMs are Tier 3 and never stored (§15). This is a property of the architecture, not a setting somebody can flip |
| **Browse retained site channels** | No screen renders Tier 2 to an admin. Access is the export gate: two named approvers, a stated reason, a bounded site and date range, written to `audit_logs`, **and the channel is told** (§15) |
| **Read a channel's history by joining it** | Join grants from the join forward. See above |
| **Delete a retained message** | The retained store is not editable from the app. Messages leave it by the retention job and no other way — an admin who can delete a record is an admin who can rewrite one |
| **Send as another user** | No impersonation path exists. Every frame is stamped with the authenticated sender |
| **Wipe a blocked user's device** | Impossible in any design where the device holds the copy (§17). The admin screen says so at the moment of blocking |
| **Suppress the export notice or the join line** | Both are unconditional. A transparency mechanism with an off switch is not one |
| **Reach another organisation** | Every query is org-scoped. An admin of one contractor has nothing in another |

### Who administers the administrator

`chat_audit` is readable by every admin in the org, not only the one who wrote the row — so a
block, an export or a join is visible to their peers. Combined with the in-channel notices, an
admin acting quietly leaves two traces they cannot remove.

### And the platform operator — you

**No access at all.** Not to messages, not to the spool, not to retained channels. A SaaS
operator with a back door is the same trust failure as an admin with one, one level up, and it
is worth stating explicitly because the customer will eventually ask. Operating the service
means Postgres and MinIO credentials, which is a real capability and should be named honestly in
the security documentation — but the product exposes no operator screen, and none should be
built.

### Permission seeding — a detail that will bite

`V2__permissions_and_system_roles.sql` grants ADMIN every permission with a `CROSS JOIN`, and
its comment says *"present and future migrations included."* **The comment is aspirational — the
statement is a one-time insert.** Every later migration that adds a permission grants it to ADMIN
explicitly, guarded by `NOT EXISTS` (see `V25__site_equipment.sql`).

So the chat permission migration must follow that convention, or admins will hold neither of
these and nobody will be able to block a user or post an announcement:

```sql
INSERT INTO permissions (id, code, description) VALUES
    (gen_random_uuid(), 'chat:restrict', 'Mute or block a user in the messenger'),
    (gen_random_uuid(), 'chat:announce', 'Post to the organisation announcements channel');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('chat:restrict', 'chat:announce')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
```

One consequence worth knowing before you ask for it: under Nirman's grant model these go to
**every** ADMIN. Withholding `chat:restrict` from some administrators and not others would need a
new role, not a new permission.

---

## 21. What a phone actually does to a socket

*Amends §4 and §6. Draft 1 said "a socket lives for hours." On a desktop tab that is true. On a
phone it is close to false, and the design has to be built on what is true.*

### The socket is a foreground optimisation, not the delivery mechanism

A PWA's connection dies constantly, and none of these are faults:

- **Backgrounding.** Press home or lock the screen and the browser freezes the page — Chrome
  within about five minutes, iOS almost immediately. The connection goes with it.
- **Network handoff.** WiFi to 4G, one cell tower to the next, and the TCP connection is gone.
- **The valley.** A phone that loses signal does not send a FIN. The server holds a socket that
  will never speak again.

So the honest model is: **the connection makes an open app feel instant. Delivery is guaranteed
by the `outbox` and by push, not by the connection.** §6's ack-after-commit and §9's
payload-first push were already doing that work; this section just stops pretending the socket is
the primary path.

### Three things that follow

**Zombie reaping, not socket count, is the server-side risk.** A phone in a tunnel leaves a
connection the server thinks is live. Heartbeat: `ping` every 30 s, two missed `pong`s (90 s) and
the connection is closed and its resources freed. Without this the process leaks connections
until it stops accepting new ones — and the symptom is "nobody can connect", days after the cause.

**Reconnect storms need jitter.** When a tower comes back or the backend restarts, every client
reconnects in the same instant. Exponential backoff **with jitter** — `min(30s, 2^n) × random(0.5,
1.5)`. Without the random factor, two hundred devices retry inside the same hundred milliseconds
and the restart you just did turns into an outage you did not.

**Capacity is not your problem.** Idle connections are cheap — a tuned JVM holds thousands. You
have tens to low hundreds of staff and perhaps forty concurrent at 9 a.m. Set
`server.tomcat.max-connections` deliberately and cap the per-connection send buffer, then stop
thinking about it. The engineering goes into *reconnect correctness*, not capacity.

### The recommendation: Server-Sent Events and POST, not WebSocket

Having established that the connection breaks constantly, the question becomes which transport
handles breaking best — and it is not WebSocket.

| | WebSocket | **SSE + POST** |
|---|---|---|
| Reconnect | Hand-written: backoff, jitter, resync | **The browser does it**, automatically |
| "What did I miss?" | Hand-written resync frame | **`Last-Event-ID` header**, sent by the browser on reconnect |
| Sending | Custom frame, custom retry, custom dedupe | `POST` with `Idempotency-Key` — a convention Nirman already has |
| Errors | Custom | RFC 7807, the contract Nirman already speaks |
| Proxies and CDNs | Upgrade handshake gets broken by intermediaries | Ordinary HTTP |
| Binary | Native | Not needed — media goes by presigned URL anyway (§7) |

The decisive one is `Last-Event-ID`. The browser reconnects on its own and tells the server the
last event it saw; the server replies with everything after it from the `outbox`. That is exactly
the resume semantics this app needs, and it arrives free rather than as the most bug-prone code
in the client.

**What survives unchanged:** the envelope, the `outbox`, fan-out on write, ack-after-commit,
`clientMsgId` dedupe, ordering, the 120-second authorisation snapshot. Only the pipe changes.
The twelve frames become one `GET /stream` and six small `POST` endpoints.

**What is lost:** typing indicators become a POST rather than a frame, so they are chattier —
throttle to one every three seconds, or drop the feature. On a site messenger "is he typing"
matters far less than "did it arrive."

**Two prerequisites, and both are real gotchas.** Nginx must have `proxy_buffering off` on the
stream route or events queue up invisibly and the feature looks broken; and HTTP/2 must be on, or
the six-connections-per-origin limit bites a user with two tabs open.

Keep WebSocket in mind only if voice or video signalling arrives later. For text, files and
receipts, SSE is the smaller and more robust thing.

---

## 22. When the device runs out of room

*Amends §8, which said "a device-side retention cap" and left the hard part unstated.*

### The numbers, because they decide the design

| | Per item | A busy site, one year, one device |
|---|---|---|
| Text message | ~500 B stored | ~10 MB |
| Photograph (§7: 700 KB ceiling) | 700 KB | **~5 GB** |

**Media outweighs text by roughly two hundred to one.** Any device retention policy that treats
them the same is solving the wrong problem. Three years of a supervisor's text is tens of
megabytes and can simply be kept. One year of photographs will fill a ₹9,000 handset.

### The fix: asymmetric retention, and it falls out of §15

| | Kept | Evicted |
|---|---|---|
| Text | **Long — 3 years, effectively forever** | Only past the window |
| Thumbnail (~20 KB) | **Kept as long as the text** | Never, in practice |
| Full-size original | 30 days, or until the budget bites | **First thing to go** |

Tapping an evicted photograph re-fetches it — **if the channel is retained**. And that is where
§15's tiers earn their keep a second time:

- **Tier 2 site and project channels** can evict originals aggressively, because the server still
  has them. The device is a cache.
- **Tier 3 direct messages cannot.** Nothing on the server holds it. So DM media is exactly what
  "Save to phone" (§8) is for, and the app must prompt at the moment of receipt — not warn at the
  moment of deletion, when it is too late.

That asymmetry should be visible in the UI: a photograph in a site channel is backed up, one in
a DM is not, and they should not look identical.

### The eviction ladder

Run on app start and after each media write, triggered at **70 % of quota** — never at 100 %.
Hitting the quota raises `QuotaExceededError` in the middle of a write, and a half-written record
is a worse outcome than a missing one.

1. Full-size media in retained channels, oldest first — re-fetchable, so free to drop
2. Full-size media in channels the user has **left** — see below
3. Full-size media in DMs older than 30 days — **warn before, not after**
4. Thumbnails past the text window
5. Text past the text window

**Never evicted:** anything from the last 7 days, anything still unsent, anything pinned (§15).

### Read the quota, and show it

`navigator.storage.estimate()` returns quota and usage. Chrome on Android grants roughly 60 % of
free disk — on a phone with 2 GB free that is 1.2 GB, not much. iOS bounds installed PWAs more
tightly still and does not publish a firm figure.

So the app needs a **Storage screen**: what is used, by which conversation, and a control to free
space. A user who cannot see the constraint cannot act on it, and the alternative is that the
browser acts for them, silently, and they lose a photograph nobody warned them about.

### Media belongs in OPFS, not IndexedDB

Text and metadata stay in Dexie. **Media bytes go to the Origin Private File System** —
better with large blobs, no structured-clone serialisation on every read, and it sidesteps
WebKit's long history of trouble storing large `Blob`s in IndexedDB. Available everywhere this
app targets. Keep the OPFS file handle as a field on the Dexie record so one store still indexes
the other.

### The long-serving member, specifically

A supervisor three years in, across six sites, is carrying channels for sites he left. He cannot
post there and they are gone from his list — but the bytes are still on his phone.

When he leaves a site, ask him once: **keep it here, or export and remove it?** Default to keeping
text and thumbnails and dropping full-size originals after 30 days. He keeps a readable record of
his own work; the five gigabytes of photographs do not follow him from posting to posting for the
rest of his employment.

### And on a new phone

§15 says retained channels re-sync bounded by his assignment dates. That could be gigabytes, over
2G, on first launch. So the re-sync is **text and thumbnails first, originals on demand** — the
conversation is readable in seconds and the photographs arrive when tapped.

### When the PWA is genuinely the wrong answer

Being honest about the ceiling: a PWA is worse than a native app at exactly the three things this
section is about — storage quota is browser-governed and smaller, background execution is nil, and
file access needs a user gesture. The tiering above makes the first one survivable rather than
solved.

If device storage becomes the binding complaint after the pilot, **the answer is Capacitor, not
the TWA in §12's Phase 7.** A TWA is still a web view with web storage limits; Capacitor gives
native filesystem access with the same React codebase. Do not reach for either pre-emptively —
but know which one solves which problem, because they are not interchangeable and choosing wrong
costs a quarter.

---

## 23. Capacitor — what it is, and why it is deferred rather than rejected

*Expands the closing note of §22, because "the answer is Capacitor" is not a useful sentence
without this.*

### What it is

Capacitor (from the Ionic team, the modern successor to Cordova) wraps an existing web app in a
real native shell — `WKWebView` on iOS, the Android WebView on Android — and gives it a bridge to
native APIs. Your `dist/` folder is **bundled into the app** and loaded from local storage rather
than fetched over HTTP. The output is a genuine `.aab` and `.ipa` for the Play Store and App
Store. The `android/` and `ios/` projects live in your repo, so you can drop into Kotlin or Swift
where a plugin does not exist.

The React code is unchanged. That is the whole pitch.

### What it would actually buy us

| | Today (PWA) | With Capacitor |
|---|---|---|
| Push | Web Push / VAPID, delivered via Chrome | **Native FCM and APNs** — high-priority messages, native channels |
| Media storage | OPFS, browser-governed quota (§22) | **`@capacitor/filesystem`** — real native storage, no quota |
| Install | Coax the user through "add to home screen" (§8) | Play Store install, which field staff find far easier |
| iOS notifications | Only in an installed PWA, no silent push (§9) | Ordinary APNs, no PWA gymnastics |

### And what it would cost

- **Release velocity, which is the serious one.** A PWA fix reaches every phone on next open. A
  Capacitor fix is build → sign → upload → store review (a day or three) → **and then wait for
  users to actually update**, which on site phones with data caps and auto-update switched off is
  genuinely months. Getting PWA-like speed back means Capacitor Live Updates or Capgo — another
  paid dependency, another moving part.
- **Two store accounts, two review processes, signing keys to protect, and macOS runners in CI**
  at a much higher minute cost than the two clean jobs you have now.
- **It is not automatically the storage fix.** The WebView still enforces web quotas on IndexedDB
  and OPFS. The unlimited storage comes from the *filesystem plugin*, which means §22's media
  layer has to be rewritten against a different API. Real work, not a checkbox.
- **Native FCM is not a cure for OEM battery management either.** MIUI and ColorOS kill native
  apps too. The gain over web push is real but smaller than it is usually sold as — and web push
  has one quiet advantage, which is that the process being woken is Chrome, and Chrome is
  generally already whitelisted.

### Why not now

**Because it is not a fork in the road.** Capacitor wraps a web app, so Phases 0–4 are identical
either way. Every hour spent on the PWA is an hour of the Capacitor app. Deciding now buys
nothing and costs the release velocity that a pilot most needs — the fortnight where you are
fixing things daily is exactly the fortnight you cannot be waiting on store review.

And both problems it solves are **hypotheses until the pilot measures them**:

- *Push reliability* — the Notification Health screen in Phase 3 exists precisely to produce this
  number. Build on the evidence, not the fear.
- *Storage* — §22's tiering (thumbnails kept, originals evicted and re-fetched from retained
  channels) is designed to keep a device well inside quota. If it works, the native filesystem
  buys nothing.

One constraint that is **not** a reason, in case somebody raises it: `CLAUDE.md` line 81 still
says the dev machine is macOS 12, which would have ruled out a current Xcode. That line is stale —
the machine is on macOS 26. Xcode is not installed, but that is a download, not a blocker.

### How to keep the option cheap — do this in Phase 3 and Phase 4

The whole argument above only holds if switching later is a swap rather than a rewrite. Two seams
make it so, and both cost about a day now:

```ts
interface PushProvider {                    // web-push impl now, @capacitor/push-notifications later
  subscribe(): Promise<Subscription>;
  onMessage(handler: (n: Notification) => void): void;
}

interface MediaStore {                      // OPFS impl now, @capacitor/filesystem later
  put(id: string, bytes: Blob): Promise<Ref>;
  get(id: string): Promise<Blob | null>;
  evict(id: string): Promise<void>;
  usage(): Promise<{ used: number; quota: number }>;
}
```

Nothing else in the app should call `navigator.serviceWorker.pushManager` or an OPFS handle
directly. With those two interfaces held, the migration is roughly **two weeks**: swap the
implementations, add the native projects, set up signing and store listings. Without them it is a
refactor across the codebase, and that is the version that costs a quarter.

### The trigger to revisit

Decide at the end of the Phase 4 pilot, on two numbers rather than on a feeling:

1. **Notification arrival rate below ~90 %** on the OEM phones your staff actually carry — as
   measured by the health screen, not reported anecdotally.
2. **Devices hitting the eviction ladder's step 3** (dropping DM media) within the first month,
   meaning the tiering did not keep them inside quota.

Either one, and Capacitor earns its two weeks. Neither, and the PWA is doing the job for a
fraction of the operational cost — which is the outcome to hope for.

---

## 24. The cheap pilot

*Supersedes §12 for the first release. §12's phases remain the plan for what follows — they are
no longer the plan for what ships first.*

The plan above is over-scoped for a trial, and building all of it before showing anybody is how
you spend three months learning something four weeks would have told you.

### What the pilot exists to answer

Two questions, and nothing else:

1. **Do notifications actually arrive** on the phones your staff carry?
2. **Will they use it** instead of WhatsApp?

Everything in the cut list below is there because it does not help answer those.

### The rule used to cut

> **Keep everything that affects whether they would adopt it. Cut everything that only affects
> running it at scale.**

A supervisor rejects the app because he cannot send a photograph — that is a finding, and cutting
photographs would have manufactured it. A supervisor never notices there is no export gate on
retained messages, because in a two-week trial nothing is retained.

### In

| | Why it cannot go |
|---|---|
| Login with Nirman credentials | No users otherwise |
| **Site channel** and **1:1 DM** | Nothing to test otherwise |
| **Text and images** | The photograph of the defect *is* the use case. Text alone will not tell you whether they would switch |
| **Web push + the Notification Health screen** | This is question one. Non-negotiable |
| **The `outbox`** — message arrives after the phone was off | Without it the app is a toy and gets rejected for a reason that teaches you nothing |
| Install gate + `storage.persist()` | Without it the trial loses data and you learn the wrong lesson |
| Image compression on the device | Reuse `nirman/frontend/src/offline/uploads.ts` almost as-is. Free — and without it a 2G upload fails and you conclude, wrongly, that nobody wants to send photographs |

### Out, for now

| Cut | Was | Costs you |
|---|---|---|
| **All retention (§15)** | Tier 1/2/3, export gate, pin-to-record | Nothing in a trial — **and it takes the counsel/DPDP review off the critical path entirely.** The single biggest saving here |
| Project channels, Announcements | §5, §19 | Site channel alone answers both questions |
| Documents | §7 | Images cover the use case; the allow-list and magic-byte sniff are a few days you can spend later |
| Media encryption at rest | §7 | Private bucket, presigned URLs, TLS. Defensible for ten known users on your own infrastructure — **must return before anyone outside the pilot** |
| Admin block / mute / restrict (§17) | | Ten known people in a trial. If somebody must go, deactivate them in Nirman |
| Admin join-on-demand (§20) | | Add an admin to the site in Nirman for the fortnight |
| The action channel (§16) | | Whole phase deferred. It depends on push being proven anyway |
| Read receipts, typing | §6 | Nobody will decide against the app over these |
| Eviction ladder, storage screen, OPFS (§22) | | Two weeks × ten users will not fill a phone. Plain IndexedDB |
| Export conversation | §8 | Nothing to export from a trial that keeps nothing |
| Multi-instance fan-out, load test | §12 Ph 4 | Ten users. One instance |
| Playwright e2e | §12 Ph 4 | Keep unit tests. Skip the harness |
| Org-wide DM directory (§19) | | Ten people on one site — the site roster *is* the directory |

### Do not cut these, however tight it gets

Each is nearly free now and expensive to retrofit. This is the list that pays for itself.

- **`clientMsgId` generated on the client, dedupe on it.** Two lines. Retrofitting deduplication
  into a live message store is not two lines.
- **Ack after the device commits, never on receipt** (§6). It is the *order* of two existing
  statements. Getting it wrong loses messages in a way that is very hard to diagnose.
- **The `outbox` as a table.** It is a table. Skipping it is how the pilot fails at the one thing
  that distinguishes it from a toy.
- **`PushProvider` and `MediaStore` interfaces** (§23). About a day. They are what makes a later
  move to Capacitor two weeks rather than a quarter.
- **Token in the request body, never the query string** (§4). Free.
- **Session epoch and site membership checked at connect** (§4). A few hours. Skipping it is a
  security hole, not a deferred feature.

### The shape of it

| Week | |
|---|---|
| **1** | Scaffold both apps. `V43` contract views, CORS line. Login, session, theme copied from Nirman |
| **2** | SSE stream and POST send (§21). `outbox` with ack-and-delete. Site channel roster from the view. Dexie store. Text working end to end |
| **3** | Images: compress, presigned upload and download, render. 1:1 DM |
| **4** | Web push, the Notification Health screen, install gate |

**Four weeks, one full-stack engineer.** Then two weeks on one real site with ten users.

New infrastructure: **none.** A `sandesh` database and a bucket, on the Postgres and MinIO that
are already running (§18).

### What a three-week version would lose

Dropping images saves about four days and destroys most of the value — a site messenger that
cannot carry a photograph of a crack is not the thing you are trying to evaluate. Dropping DMs
saves about two. Neither is worth it. **Four weeks is the honest floor for a pilot that can
answer its own questions.**

### The one risk of cutting this far

A thin pilot can be rejected for reasons that do not generalise. Guard against it by saying, in
the app and to the ten users, what is deliberately missing and what is coming — *"documents and
saved history arrive after the trial"* — so their feedback is about the thing you are testing
rather than about the gaps you already know are there.
