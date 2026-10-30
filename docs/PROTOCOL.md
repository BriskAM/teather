# Protocol v1

TCP port 41691, UTF-8 JSON objects separated by LF. A frame is bounded to 2 MiB on Mac and 64 KiB for commands on Android. The Android snapshot builder has the same 2 MiB outbound ceiling. Invalid/oversized frames close the connection. Tailscale supplies authenticated encrypted transport; the application key adds pairing authorization.

1. Android sends `hello`: `version: 1`, `token` (64 lowercase hex characters), `deviceId` (installation UUID), `name` (device model).
2. Mac validates key and phone binding and replies `ready` with `version: 1` and supported `features` (including `fileShare` in 0.2.0). One active connection is allowed. Reconnecting the same phone replaces its previous connection.
3. Android sends `snapshot` with `items`, each containing `key`, `revision`, `app`, `package`, `title`, `body`, `dismissible`, and `sound`.
4. Notification callbacks cause `delta` with changed `items` and `removed` keys. Stable items are omitted.
5. Mac sends `dismiss` with the notification `key` and exact `revision`. Android looks up the current notification, checks app selection, protection, local-only preferences and revision, then requests cancellation. It responds with `dismissResult`: `requested`, `gone`, `stale`, `protected`, or `unavailable`.
6. The subsequent Android removal delta confirms actual cancellation. Mac does not manufacture a new dismissal in response to a removal.
7. Android sends `ping` every 120 seconds when idle; Mac replies `pong`. Notification batches also send a ping. Authentication has a 12-second Android timeout and 10-second Mac timeout; idle connection timeout is 300 seconds. Android retries at 1, 2, 4, 8, 16, 32, 64, then 120 seconds.

A revision is SHA-256 over the Android notification key, post time, title and message body. IDs are scoped to one phone. Mac Notification Center identifiers are SHA-256 of the Android key. Content/settings changes update presentation; dismissal intent is tied to the source content revision.

A disconnected Mac dismissal is retained in memory for at most ten minutes and retried only against a matching snapshot item. New revisions cancel old requests. Mac process termination clears pending requests. Reconnect snapshots restore current state without sounds; notification banners remain controlled by macOS settings.

`unavailable` reports a disconnected Android notification listener. It does not erase the Mac's mirror as though all source notifications were deleted. `test` is a paired-client diagnostic that asks the Mac to issue a local test notification. No general remote execution or arbitrary URL opening is supported.

## Scoped folder endpoint (0.2.0)

Android sends `fileShare` only when the Mac advertises that capability, after authentication and on changes, with `enabled`, `name`, and `writable`. The Mac uses the authenticated TCP peer's IPv4 address, not a client-supplied URL, to mount `http://<phone>:41692/Kunnect/` through NetFS. The username is `kunnect`; the password is the existing pairing secret. It is supplied directly to NetFS rather than embedded in a URL or process arguments. Tailscale encrypts transport. Tailnet policy must allow Mac → phone TCP 41692.

The Android endpoint accepts only the paired Mac's configured IPv4 (debug builds additionally accept loopback for emulator tests), requires Basic authentication on every request, and resolves paths through the persisted SAF tree. Traversal components and encoded path separators are rejected. It is off unless the user has explicitly selected and enabled a folder and the connection foreground service is running.

Read operations: `OPTIONS`, `PROPFIND` (depth 0/1 only), `HEAD`, and `GET` including single byte ranges. Write opt-in enables `PUT`, `MKCOL`, `DELETE`, `MOVE`, file `COPY`, depth-0 file `LOCK`/`UNLOCK`. Locks expire lazily after three minutes; there is no cleanup timer. Finder metadata/extended-attribute writes and recursive server-side folder copies are unsupported. MOVE never overwrites an existing destination. SAF-provider capabilities determine whether cross-directory moves succeed.

Request headers are capped at 32 KiB, command lines at 8 KiB, directory listings at 10,000 entries, and uploads at 8 GiB. Up to four worker threads stream through fixed 64 KiB buffers and expire after 20 idle seconds. The acceptor blocks while idle; there are no scans or file-server heartbeats. PUT stages complete data in a sibling temporary document before a backup/rename commit; interrupted uploads preserve the previous document. SAF does not provide a universal crash-atomic replacement API.

Older Mac companions without the `fileShare` capability keep receiving notification messages only, with the legacy 30-second heartbeat/75-second Android timeout. Updated pairs use the two-minute heartbeat/five-minute timeout. This allows updating either app first without breaking notification mirroring.
