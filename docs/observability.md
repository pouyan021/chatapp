# Local observability

Start Docker and use Java 25. From the repository root:

```sh
docker compose --profile observability up -d --wait
./gradlew bootRun --args='--spring.profiles.active=dev,observability'
```

Open [Grafana](http://localhost:3001/dashboards) and the [chat playground](http://localhost:8080/dev/chat).
The **Chatapp** folder contains three provisioned dashboards:

- **Chat · Overview**: message command outcomes, authenticated connections, processing latency, dispatch submissions and failures.
- **Chat · API and dependencies**: REST routes/statuses, Cassandra and object-storage operations, JVM memory, GC, CPU and uptime.
- **Chat · Failure investigation**: WARN/ERROR logs, failures by stage/code, server-requested close reasons, authentication/protocol rejections, and trace search.

Anonymous Grafana access is read-only. For local administration, the image's default login is `admin` / `admin`. Grafana and OTLP ports bind only to loopback. This stack is for local development.

The `observability` Spring profile also activates the Compose profile through Spring Boot. The telemetry service is marked ignored for service-connection auto-configuration: explicit OTLP settings keep local endpoints predictable. No telemetry service or exports are required for ordinary `dev` startup.

## Follow a message

1. Use the playground in two tabs as described in [local-chat.md](local-chat.md). Create a chat and send messages. Retry the same client ID once.
2. Open Overview. Allow roughly 15 seconds for export and collection. Rate panels need multiple samples. Accepted counts include the retry; it does not represent a new stored message.
3. Open Failure investigation and select a trace from Trace search. Cassandra operations and dispatch appear below the STOMP command. REST requests have their own traces.
4. Use a log entry's `trace_id` link to open its trace, or the trace's linked logs action to return to matching logs. Successful traces may have no associated application logs at the default INFO level.
5. For successful message diagnostics, start with `--logging.level.com.kutumlabs.chatapp.socket=DEBUG`. Search Loki structured metadata using `requestId`, `sessionId`, `messageId` or `chatId`; IDs are not stream labels.

For example, in Grafana Explore with the Loki data source:

```logql
{service_name="chatapp"} | requestId = "your-request-id"
```

Request IDs are sanitized to 128 characters of letters, digits, `.`, `_`, `:`, and `-`. Other characters become `_`. Correlation is for diagnostics, not authentication or uniqueness. Neither message text nor tokens, push tokens, raw frames, or signed URLs are logged by application instrumentation.

## Signal semantics

| Signal | Meaning |
| --- | --- |
| `chat.messages.accepted` / `chat.messages.rejected` | Completed service send attempts; retries count again. Payload conversion failures before the service are represented by STOMP rejection/failure observations. |
| `chat.delivery.submitted` | Per-session submission to Spring messaging; does not confirm a subscription, transport write, or client receipt. |
| `chat.delivery.failures` | Synchronous dispatch failures, including participant enumeration or individual session submission. |
| `chat.reply.failures` | Result/info submission failed; no recursive retry and no rollback of a persisted message. |
| `chat.persistence.failures` | Unexpected exception at a `ChatStore` boundary, counted once. |
| `chat.failures{stage,code}` | Failure events at named boundaries. One failed operation can appear at both dependency and request boundaries; do not sum stages as unique failed messages. |
| `chat.connections.active` | Authenticated connections on the selected server instance. |
| `chat.connections.close.requests{reason}` | Server-requested closures, grouped by WebSocket close-code category; not a count of all remote disconnects. |
| `chat.stomp.rejected{stage}` | Authentication/protocol rejection before application handling. Heartbeats are not timed or logged. |

`chat.stomp`, `chat.operation`, `chat.persistence`, `chat.storage`, and `chat.dispatch` are duration observations. Outcomes are `success`, `rejected`, or `error`; dependency operation names are fixed Java method names. Prometheus translates dots to underscores and adds units/type suffixes, e.g. `chat_operation_seconds_bucket`.

Dashboards show missing data as **No telemetry**, not zero errors. An unexercised operation may have no series. Object-storage metrics cover server-side signing/verification, not direct client upload/download transfer time. Histogram percentiles describe server operation durations. Traces use 100% sampling in development and 10% otherwise; metrics and error logs do not depend on trace sampling.

## Configuration and access

- `CHAT_OTLP_ENDPOINT` defaults to `http://localhost:4318`; use the base URL without a trailing slash. The profile appends `/v1/metrics`, `/v1/traces`, and `/v1/logs`.
- `CHAT_INSTANCE_ID` overrides the default `chatapp-<server.port>`. Set distinct values for multiple deployed instances. Metrics, logs and traces share this identifier.
- Spring's `management.opentelemetry.*` properties configure exporter headers/timeouts. `management.otlp.metrics.export.step` defaults to five seconds in this profile.
- Management health and Prometheus are accessible on `127.0.0.1:8081`; other management endpoints remain denied. Use `--management.server.port=18081` if that port is occupied. Keep the management port separate from the application port.
- The app pushes all signals; Docker does not scrape the host management endpoint. Collector outages may emit exporter warnings but do not block message processing.
- Console logs are structured JSON normally and readable text under `dev`. OTLP export adds an OpenTelemetry Logback appender. Its version is aligned with Spring Boot's OpenTelemetry SDK; update both together.

## Troubleshooting and reset

```sh
docker compose ps telemetry
docker compose logs --tail=100 telemetry
curl http://localhost:8081/actuator/health
```

If dashboards show no data, check the Spring profile, exporter startup logs, selected service/instance, and time range. Generate chat/API activity and wait for multiple exports. Dashboard JSON files and their provider live in `observability/`; edits are reloaded automatically.

Stop only telemetry with `docker compose stop telemetry`. Data persists in the `chatapp_telemetry-data` volume. To intentionally erase local telemetry, first stop the app, then:

```sh
docker compose rm -sf telemetry
docker volume rm chatapp_telemetry-data
docker compose --profile observability up -d --wait telemetry
```

These commands target telemetry only; do not use `docker compose down -v`, which would also remove chat data. A custom Compose project name changes the volume prefix.

Run verification with Docker available:

```sh
./gradlew test spotlessCheck
```
