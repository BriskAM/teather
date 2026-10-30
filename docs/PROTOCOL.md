# Protocol v1

TCP port 41691, UTF-8 JSON objects separated by LF. A frame is bounded to 2 MiB on Mac and 64 KiB for commands on Android. The Android snapshot builder has the same 2 MiB outbound ceiling. Invalid/oversized frames close the connection. Tailscale supplies authenticated encrypted transport; the application key adds pairing authorization.

1. Android sends `hello`: `version: 1`, `token` (64 lowercase hex characters), `deviceId` (installation UUID), `name` (device model).
2. Mac validates key and phone binding and replies `ready` with `version: 1`. One active connection is allowed. Reconnecting the same phone replaces its previous connection.
3. Android sends `snapshot` with `items`, each containing `key`, `revision`, `app`, `package`, `title`, `body`, `dismissible`, and `sound`.
4. Notification callbacks cause `delta` with changed `items` and `removed` keys. Stable items are omitted.
5. Mac sends `dismiss` with the notification `key` and exact `revision`. Android looks up the current notification, checks app selection, protection, local-only preferences and revision, then requests cancellation. It responds with `dismissResult`: `requested`, `gone`, `stale`, `protected`, or `unavailable`.
6. The subsequent Android removal delta confirms actual cancellation. Mac does not manufacture a new dismissal in response to a removal.
7. Android sends `ping` every 30 seconds when idle; Mac replies `pong`. Notification batches also send a ping. Authentication has a 12-second Android timeout and 10-second Mac timeout; idle connection timeout is 75 seconds. Android retries at 1, 2, 4, 8, 16, then 30 seconds.

A revision is SHA-256 over the Android notification key, post time, title and message body. IDs are scoped to one phone. Mac Notification Center identifiers are SHA-256 of the Android key. Content/settings changes update presentation; dismissal intent is tied to the source content revision.

A disconnected Mac dismissal is retained in memory for at most ten minutes and retried only against a matching snapshot item. New revisions cancel old requests. Mac process termination clears pending requests. Reconnect snapshots restore current state without sounds; notification banners remain controlled by macOS settings.

`unavailable` reports a disconnected Android notification listener. It does not erase the Mac's mirror as though all source notifications were deleted. `test` is a paired-client diagnostic that asks the Mac to issue a local test notification. No general remote execution or arbitrary URL opening is supported.
