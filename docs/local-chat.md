# Try the chat backend locally

Install Java 25 and start Docker, then run from the project root:

```sh
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Compose applies the current initialization and waits for Cassandra's schema and MinIO's versioned bucket. The first startup can take a few minutes. Keep the Gradle command running.

The dev profile uses a separate `chatapp_dev` keyspace initialized from the current schema. This preserves any existing `chatapp` data, including tables created by older UUID-based versions. Demo data persists in the existing Cassandra volume; no tables are dropped or migrated.

- Playground: http://localhost:8080/dev/chat
- Swagger: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Socket protocol: [stomp.md](stomp.md) (also served at `/dev/protocol`)

Use `localhost` or `127.0.0.1`, consistently. The dev server binds to loopback. The page bundles its browser dependencies, so it needs no frontend server or runtime CDN access. A different port can be selected with `--server.port=8081`; dev JWT and allowed-origin defaults follow that port.

For optional metrics dashboards, searchable logs, and traces, see [local observability](observability.md).

## Two-tab walkthrough

1. Open the playground in two tabs. Keep Alice in the first; choose Bob in the second. Click **Connect** in both and check their connected status and separate device IDs.
2. In Alice's tab, click **Create conversation**. In Bob's tab, click **Refresh** and choose the same conversation. Creating a chat is explicit; each click creates a new chat.
3. Send a few messages both ways. Each should appear once in both tabs. Open the request/event log to see STOMP sends, correlated acceptance replies, and live events. Acceptance means persisted; it does not promise delivery to every participant.
4. Click **Disconnect** in Bob's tab. Send several messages as Alice, then reconnect Bob. History should fill the gap automatically, merging with live messages without duplicates. **Load older** pages backward through history.
5. Click **Retry last send (same ID)** in Alice's tab. The acceptance should refer to the same saved message, and the conversation should still show one copy. An uncertain send has a separate retry control. Retries preserve the original content and client ID.
6. Reload both tabs, select the identities again, connect, and choose the conversation. The messages should still be present. Alice/Bob user IDs are fixed across server restarts. Pending unsaved browser state does not survive a reload.

Reconnection is manual. Tokens expire after one hour; reconnect obtains a fresh token. Restarting the server rotates the development signing key, so reconnect both tabs and copy a new token for Swagger after a restart.

## Exercise REST with Swagger

In the playground select the desired identity and click **Copy token for Swagger**. In Swagger, click **Authorize** and paste the token without the `Bearer` prefix. Tokens are kept in memory, and are not included in the playground event log.

Try `GET /api/chats`, then copy a real chat ID into `GET /api/chats/{chatId}/messages`. To paginate, pass the returned `nextCursor` unchanged with the same chat ID and limit. The API returns newest messages first; the playground displays them chronologically.

To check validation, call `POST /api/chats` with a blank name. Expect HTTP 400 and an `INVALID_REQUEST` response. The default valid create example is for Alice creating a chat with Bob; when authorized as Bob, use Alice's ID instead:

| Identity | User ULID |
| --- | --- |
| Alice | `01ARZ3NDEKTSV4RRFFQ69G5FAY` |
| Bob | `01ARZ3NDEKTSV4RRFFQ69G5FAZ` |

Swagger documents the existing media, read-state, device registration, and connection APIs too. Media bytes go directly to object storage using signed URLs; text-only playground controls do not implement that flow. Sending messages remains STOMP-only.

## Development boundaries and checks

The demo issuer (`POST /dev/token`, Alice/Bob only), public JWKS, playground, protocol page, and Swagger access are enabled only by the `dev` profile. JWT signatures, issuer, audience, user identity, and expiration are still validated. Development token responses use `Cache-Control: no-store`. Do not deploy with the `dev` profile.

Run automated checks with Docker running:

```sh
./gradlew test
./gradlew spotlessCheck
```

The dev web tests check token issuance, real signature validation, API authentication, OpenAPI contracts, and profile isolation. The existing integration suite exercises real Cassandra, MinIO, and STOMP traffic.
