# Chat client protocol (STOMP over WebSocket)

Connect to `ws://<host>/ws/chat` (`wss://` in production) on the same server as the REST API. This is STOMP 1.2 over raw JSON WebSocket text frames (no SockJS fallback). Use any standard STOMP client for browser, React Native, Kotlin, or Swift.

Database records and the REST chat, history, media, and connection APIs are unchanged.

## Establishing a connection

Send one STOMP `CONNECT` frame with:

- `Authorization: Bearer <jwt>` — required. Do not send credentials on any later frame; the server closes the connection if it sees credentials outside `CONNECT`.
- `device-id: <id>` — required, 1–128 letters, digits, dots, underscores, or hyphens.
- `heart-beat: <send>,<receive>` — required, both values positive and each no greater than `chat.socket.heartbeat-interval` (30,000 ms by default). The server enables the STOMP heartbeat at that interval; a connection that goes silent for `chat.socket.idle-timeout` (90s by default) is closed.

JWT validation uses the same issuer, audience, `user_id` ULID claim, and expiration requirements as REST. One authenticated user and device are bound to each connection. Establish a new connection when refreshing credentials — the server closes the connection when the JWT expires.

Browsers must originate from `chat.security.allowed-origins`. Native clients may omit `Origin`; if they send it, it must be allowed. A successful WebSocket upgrade and `CONNECTED` frame do not by themselves mean anything beyond transport-level auth: only `CONNECT` itself is validated against the JWT, so treat the connection as ready once `CONNECTED` arrives.

After `CONNECTED`, subscribe to your reply queues before sending anything:

- `SUBSCRIBE /user/queue/results` — replies to `message.send`.
- `SUBSCRIBE /user/queue/connection` — replies to `connection.info`.
- `SUBSCRIBE /user/queue/messages` — live chat messages.

No other subscription destination is allowed; subscribing anywhere else, duplicating a destination, or subscribing with an `ack` mode other than `auto` closes the connection. `UNSUBSCRIBE` from `/user/queue/messages` at any time to pause delivery (e.g. while backgrounded); `SUBSCRIBE` again to resume — this does not disconnect and does not replay what was missed (see Reconnection below).

## Requests

Every request is a STOMP `SEND` with a `request-id` header (any nonblank string up to 128 characters, unique per in-flight request on that connection) and a JSON body. The server correlates the reply on `/user/queue/results` or `/user/queue/connection` by echoing the same `request-id` header. Only `/app/v1/message.send` and `/app/v1/connection.info` are valid `SEND` destinations; anything else closes the connection.

### `/app/v1/connection.info`

Send an empty body. The reply on `/user/queue/connection` is:

```json
{
  "sessionId": "server-generated-id",
  "deviceId": "phone-1",
  "connectedAt": "2026-09-09T10:00:00Z",
  "lastSeenAt": "2026-09-09T10:00:00Z",
  "expiresAt": "2026-09-09T11:00:00Z"
}
```

`lastSeenAt` records application request activity; STOMP heartbeats alone do not update it. Session IDs also work with `GET /api/connections` and `DELETE /api/connections/{sessionId}`. A user can list or revoke only their own connections.

### `/app/v1/message.send`

```json
{
  "clientMessageId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "chatId": "01ARZ3NDEKTSV4RRFFQ69G5FAW",
  "text": "Hello",
  "media": null
}
```

Generate a new ULID `clientMessageId` for each logical message. Supply exactly one of nonblank `text` or `media`. For media, use `{"uploadId":"<ULID>","versionId":"<immutable-object-version>"}` from the existing REST upload flow — media bytes are uploaded directly to object storage, not over the socket. The default text limit is `chat.socket.max-text-bytes` (8,192 UTF-8 bytes by default).

The reply on `/user/queue/results` has exactly one non-null field:

```json
{
  "acceptance": {
    "clientMessageId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "messageId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
    "createdAt": "2026-09-09T10:00:01Z",
    "chatId": "01ARZ3NDEKTSV4RRFFQ69G5FAW"
  },
  "error": null
}
```

```json
{
  "acceptance": null,
  "error": {
    "code": "FORBIDDEN",
    "message": "Chat membership is required",
    "retryable": false
  }
}
```

Business errors preserve `code`, `message`, and `retryable`. Unexpected persistence errors return `TEMPORARILY_UNAVAILABLE` with `retryable: true`, without exposing internal exceptions. A malformed body (e.g. invalid JSON) replies with `INVALID_REQUEST` on `/user/queue/results` and leaves the connection usable. On an uncertain send (including a lost reply), retry the **same content and clientMessageId**; reusing that ID with different content returns `CONFLICT`.

Acceptance confirms persistence, not delivery to every participant. Live delivery failure never undoes a committed message. The acceptance reply and the sender's own live message on `/user/queue/messages` can arrive in either order.

## Live messages

Once subscribed to `/user/queue/messages`, each frame is the existing `MessageView` JSON shape:

```json
{
  "chatId": "01ARZ3NDEKTSV4RRFFQ69G5FAW",
  "createdAt": "2026-09-09T10:00:01Z",
  "messageId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
  "senderId": "01ARZ3NDEKTSV4RRFFQ69G5FAY",
  "text": "Hello",
  "media": null
}
```

Delivery covers all chats addressed to the authenticated user, including their own sends and sends from their other devices — there is no client-supplied user ID. Every connection for that user (multiple tabs, multiple devices) is delivered to independently. Retried sends can repeat live events, so deduplicate by `messageId`.

## Reconnection and limits

1. Obtain a valid JWT, `CONNECT`, subscribe to all three queues, then fetch connection info.
2. Retrieve missed messages through the existing REST history API, paging far enough to overlap locally saved history. Merge and deduplicate by `messageId`.
3. Retry pending sends using their original `clientMessageId` and content.

There is no durable replay cursor on the live queue — a disconnect (transport failure, server restart, token expiry, oversized frame, or protocol violation) drops any messages sent while offline; catch up via REST history, not the socket. Reconnect with bounded exponential backoff and jitter for transient failures. Stop automatic retries for permanent authentication or protocol errors until corrected. Background mobile notifications require a separate push notification integration.

`chat.socket.max-frame-bytes` (65,536 by default) bounds both inbound and outbound WebSocket frames; a client that exceeds it is disconnected. `chat.socket.send-buffer-bytes` and `chat.socket.send-time-limit` bound how much unacknowledged outbound data the server queues per connection before dropping a slow client.

Fan-out and connection tracking are local to one server instance (`ConnectionRegistry`, `LocalMessageDispatcher`). Multiple instances require a separate shared delivery mechanism.

## Server integration and verification

`StompConfiguration` wires the `/ws/chat` STOMP endpoint onto the existing Spring MVC (servlet) server, running client message handling on a virtual-thread executor (`SimpleAsyncTaskExecutor` with `setVirtualThreads(true)`) rather than the reactive stack. `StompAuthenticationInterceptor` performs the credential and destination checks described above on the client inbound channel and closes the connection on any violation; `ConnectionRegistry` tracks live sessions and expires them on a schedule.

`ChatIntegrationTests` and `support/TestStomp` show a Java STOMP client and exercise actual WebSocket traffic against Cassandra and MinIO. Run `./gradlew test` and `./gradlew spotlessCheck`.

Reference: [Spring STOMP support](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html).
