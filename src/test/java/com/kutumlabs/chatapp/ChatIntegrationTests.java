package com.kutumlabs.chatapp;

import static org.assertj.core.api.Assertions.*;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.chat.*;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.media.ObjectStorage;
import com.kutumlabs.chatapp.socket.ConnectionRegistry;
import com.kutumlabs.chatapp.socket.SendResult;
import com.kutumlabs.chatapp.support.TestJwtIssuer;
import com.kutumlabs.chatapp.support.TestStomp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.docker.compose.enabled=false",
            "chat.socket.heartbeat-interval=500ms",
            "chat.socket.idle-timeout=3s",
            "chat.storage.download-url-ttl=1s"
        })
class ChatIntegrationTests {
    static final TestJwtIssuer ISSUER = new TestJwtIssuer();

    @Container
    @ServiceConnection
    static final CassandraContainer CASSANDRA = new CassandraContainer("cassandra:5.0").withInitScript("schema.cql");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", "chatapp")
            .withEnv("MINIO_ROOT_PASSWORD", "chatapp123")
            .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "http://localhost:3000")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chat.security.issuer", ISSUER::issuer);
        registry.add("chat.security.jwk-set-uri", ISSUER::jwks);
        registry.add("chat.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add(
                "chat.storage.public-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("chat.storage.access-key", () -> "chatapp");
        registry.add("chat.storage.secret-key", () -> "chatapp123");
    }

    @LocalServerPort
    int port;

    @Autowired
    ChatService chats;

    @Autowired
    ChatStore store;

    @Autowired
    ConnectionRegistry connections;

    @Autowired
    S3Client s3;

    private RestTestClient web;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        web = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        http = HttpClient.newHttpClient();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("chat-media").build());
        } catch (S3Exception error) {
            if (error.statusCode() != 409) throw error;
        }
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket("chat-media")
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    @AfterEach
    void closeClient() {
        http.close();
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    private String token(Ulid user) {
        return ISSUER.token(user);
    }

    private ChatView create(Ulid owner, Ulid other) {
        return web.post()
                .uri("/api/chats")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .body(new CreateChat("Test chat", "DIRECT", List.of(other.toString())))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(ChatView.class)
                .returnResult()
                .getResponseBody();
    }

    private URI socketUri() {
        return URI.create("ws://localhost:" + port + "/ws/chat");
    }

    private TestStomp connect(Ulid user, String device) {
        return new TestStomp(socketUri(), token(user), device);
    }

    @Test
    void deliversToAllTabsAndDevicesAndRecoversHistoryAfterAnOfflineSend() {
        var alice = UlidCreator.getMonotonicUlid();
        var bob = UlidCreator.getMonotonicUlid();
        var chat = create(alice, bob);
        String messageId;
        try (var sender = connect(alice, "browser");
                var senderOther = connect(alice, "phone");
                var receiver = connect(bob, "browser");
                var receiverTab = connect(bob, "browser")) {
            assertThat(connections.list(bob)).hasSize(2);
            String clientId = UlidCreator.getMonotonicUlid().toString();
            var command = new SendCommand(clientId, chat.chatId(), "hello", null);
            var accepted = sender.send(command).acceptance();
            messageId = accepted.messageId();
            for (var socket : List.of(sender, senderOther, receiver, receiverTab)) {
                assertThat(socket.awaitMessage().messageId()).isEqualTo(messageId);
            }
            assertThat(sender.send(command).acceptance().messageId()).isEqualTo(messageId);
        }
        try (var sender = connect(alice, "browser")) {
            sender.send(
                    new SendCommand(UlidCreator.getMonotonicUlid().toString(), chat.chatId(), "offline message", null));
        }
        var history = web.get()
                .uri("/api/chats/" + chat.chatId() + "/messages?limit=1")
                .headers(headers -> headers.setBearerAuth(token(bob)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(History.class)
                .returnResult()
                .getResponseBody();
        assertThat(history.messages()).hasSize(1);
        assertThat(history.messages().getFirst().text()).isEqualTo("offline message");
        var older = web.get()
                .uri(builder -> builder.path("/api/chats/" + chat.chatId() + "/messages")
                        .queryParam("limit", 1)
                        .queryParam("cursor", history.nextCursor())
                        .build())
                .headers(headers -> headers.setBearerAuth(token(bob)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(History.class)
                .returnResult()
                .getResponseBody();
        assertThat(older.messages()).extracting(MessageView::messageId).containsExactly(messageId);
    }

    @Test
    void chatSummaryReadStateAndDeviceRegistrationWork() {
        var alice = UlidCreator.getMonotonicUlid();
        var bob = UlidCreator.getMonotonicUlid();
        var chat = create(alice, bob);

        StoredMessage message;
        try (var sender = connect(alice, "browser")) {
            var accepted = sender.send(
                            new SendCommand(UlidCreator.getMonotonicUlid().toString(), chat.chatId(), "hi bob", null))
                    .acceptance();
            message = store.findMessage(
                            ChatModels.id(chat.chatId()), accepted.createdAt(), ChatModels.id(accepted.messageId()))
                    .orElseThrow();
        }

        var summaries = web.get()
                .uri("/api/chats")
                .headers(headers -> headers.setBearerAuth(token(bob)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ChatSummaryView[].class)
                .returnResult()
                .getResponseBody();
        assertThat(summaries).extracting(ChatSummaryView::chatId).contains(chat.chatId());
        var summary = List.of(summaries).stream()
                .filter(s -> s.chatId().equals(chat.chatId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.lastMessagePreview()).isEqualTo("hi bob");

        web.post()
                .uri("/api/chats/" + chat.chatId() + "/read")
                .headers(headers -> headers.setBearerAuth(token(bob)))
                .body(new MarkReadCommand(message.messageId().toString()))
                .exchange()
                .expectStatus()
                .isNoContent();

        web.put()
                .uri("/api/devices/phone-1/push-token")
                .headers(headers -> headers.setBearerAuth(token(bob)))
                .body(new RegisterDevice("token-abc"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void concurrentRetriesUseOneCassandraMessageAndConflictingPayloadFails() {
        var owner = UlidCreator.getMonotonicUlid();
        var chat = create(owner, UlidCreator.getMonotonicUlid());
        String clientId = UlidCreator.getMonotonicUlid().toString();
        var command = new SendCommand(clientId, chat.chatId(), "same", null);
        List<StoredMessage> messages = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(_ -> executor.submit(() -> chats.send(owner, command)))
                    .toList();
            for (var task : tasks) messages.add(task.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertThat(messages.stream().map(StoredMessage::messageId).distinct().toList())
                .hasSize(1);
        assertThat(store.history(ChatModels.id(chat.chatId()), 100, null).messages())
                .hasSize(1);
        assertThatThrownBy(() -> chats.send(owner, new SendCommand(clientId, chat.chatId(), "different", null)))
                .isInstanceOf(ChatFailure.class);
    }

    @Test
    void validatesJwtMembershipSetupAndOrigin() {
        var owner = UlidCreator.getMonotonicUlid();
        var chat = create(owner, UlidCreator.getMonotonicUlid());
        var outsider = UlidCreator.getMonotonicUlid();
        web.get().uri("/api/connections").exchange().expectStatus().isUnauthorized();
        for (String invalid : List.of(
                ISSUER.token(owner.toString(), "wrong", ISSUER.issuer(), Duration.ofMinutes(1)),
                ISSUER.token(owner.toString(), "chatapp", "https://wrong.test", Duration.ofMinutes(1)),
                ISSUER.token("not-a-ulid", "chatapp", ISSUER.issuer(), Duration.ofMinutes(1)),
                ISSUER.token(owner.toString(), "chatapp", ISSUER.issuer(), Duration.ofMinutes(-5)))) {
            assertThatThrownBy(() -> new TestStomp(socketUri(), invalid, "browser"))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> new TestStomp(socketUri(), null, "browser")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TestStomp(socketUri(), token(owner), "invalid device"))
                .isInstanceOf(RuntimeException.class);
        web.get()
                .uri("/api/chats/" + chat.chatId() + "/messages")
                .headers(headers -> headers.setBearerAuth(token(outsider)))
                .exchange()
                .expectStatus()
                .isForbidden();
        try (var socket = connect(outsider, "browser")) {
            var result = socket.send(
                    new SendCommand(UlidCreator.getMonotonicUlid().toString(), chat.chatId(), "unauthorized", null));
            assertThat(result.error().code()).isEqualTo("FORBIDDEN");
        }
        assertThatThrownBy(() -> http.newWebSocketBuilder()
                        .header("Origin", "https://untrusted.test")
                        .buildAsync(socketUri(), new java.net.http.WebSocket.Listener() {})
                        .join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    void closesExpiredConnectionsAndOnlyAllowsOwnSessionRevocation() {
        var owner = UlidCreator.getMonotonicUlid();
        var outsider = UlidCreator.getMonotonicUlid();
        try (var active = connect(owner, "browser")) {
            String sessionId = active.session().sessionId();
            web.delete()
                    .uri("/api/connections/" + sessionId)
                    .headers(headers -> headers.setBearerAuth(token(outsider)))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            web.delete()
                    .uri("/api/connections/" + sessionId)
                    .headers(headers -> headers.setBearerAuth(token(owner)))
                    .exchange()
                    .expectStatus()
                    .isNoContent();
            active.awaitClosed();
        }
        String shortToken = ISSUER.token(owner.toString(), "chatapp", ISSUER.issuer(), Duration.ofSeconds(3));
        try (var expiring = new TestStomp(socketUri(), shortToken, "browser")) {
            expiring.awaitClosed();
        }
        awaitNoConnections(owner);
    }

    @Test
    void closesSilentConnectionsUsingStompHeartbeats() {
        var owner = UlidCreator.getMonotonicUlid();
        try (var silent = new TestStomp(socketUri(), token(owner), "silent", false, null)) {
            silent.awaitClosed();
        }
        awaitNoConnections(owner);
    }

    @Test
    void rejectsUnauthorizedDestinationsAndCredentialChanges() {
        var owner = UlidCreator.getMonotonicUlid();
        for (String destination : List.of("/queue/messages", "/user/other/queue/messages", "/topic/all")) {
            try (var client = connect(owner, "phone")) {
                client.stomp().subscribe(destination, new org.springframework.messaging.simp.stomp.StompFrameHandler() {
                    public java.lang.reflect.Type getPayloadType(
                            org.springframework.messaging.simp.stomp.StompHeaders h) {
                        return String.class;
                    }

                    public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders h, Object p) {
                        throw new AssertionError("Unauthorized delivery");
                    }
                });
                client.awaitClosed();
            }
        }
        try (var client = connect(owner, "phone")) {
            var headers = new org.springframework.messaging.simp.stomp.StompHeaders();
            headers.setDestination("/app/v1/connection.info");
            headers.add("request-id", "change");
            headers.add("Authorization", "Bearer " + token(UlidCreator.getMonotonicUlid()));
            client.stomp().send(headers, "");
            client.awaitClosed();
        }
        try (var client = connect(owner, "phone")) {
            client.stomp().send("/queue/messages", "forged");
            client.awaitClosed();
        }
        awaitNoConnections(owner);
    }

    @Test
    void malformedPayloadReturnsCorrelatedErrorAndConnectionRemainsUsable() {
        var owner = UlidCreator.getMonotonicUlid();
        try (var client = connect(owner, "phone")) {
            var result = client.request("/app/v1/message.send", "{broken", SendResult.class);
            assertThat(result.error().code()).isEqualTo("INVALID_REQUEST");
            assertThat(client.request("/app/v1/connection.info", "", ConnectionRegistry.SessionView.class)
                            .sessionId())
                    .isEqualTo(client.session().sessionId());
        }
        awaitNoConnections(owner);
    }

    @Test
    void unsubscribeStopsDeliveryWithoutDisconnectingAndResubscribeWorks() {
        var alice = UlidCreator.getMonotonicUlid();
        var bob = UlidCreator.getMonotonicUlid();
        var chat = create(alice, bob);
        try (var sender = connect(alice, "sender");
                var receiver = connect(bob, "receiver")) {
            receiver.unsubscribeMessages();
            receiver.request("/app/v1/connection.info", "", ConnectionRegistry.SessionView.class);
            assertThat(sender.send(new SendCommand(
                                    UlidCreator.getMonotonicUlid().toString(), chat.chatId(), "missed", null))
                            .acceptance())
                    .isNotNull();
            receiver.subscribeMessages();
            receiver.request("/app/v1/connection.info", "", ConnectionRegistry.SessionView.class);
            var accepted = sender.send(
                            new SendCommand(UlidCreator.getMonotonicUlid().toString(), chat.chatId(), "live", null))
                    .acceptance();
            assertThat(receiver.awaitMessage().messageId()).isEqualTo(accepted.messageId());
            assertThat(receiver.pollMessage()).isNull();
        }
    }

    @Test
    void oversizedMessagesAndDuplicateSubscriptionsCloseTheConnection() {
        var owner = UlidCreator.getMonotonicUlid();
        try (var client = connect(owner, "phone")) {
            client.subscribeMessages();
            client.awaitClosed();
        }
        try (var client = connect(owner, "phone")) {
            var headers = new org.springframework.messaging.simp.stomp.StompHeaders();
            headers.setDestination("/app/v1/message.send");
            headers.add("request-id", "large");
            client.stomp().send(headers, "x".repeat(70000));
            client.awaitClosed();
        }
        awaitNoConnections(owner);
    }

    private void awaitNoConnections(Ulid user) {
        TestStomp.await(() -> connections.list(user).isEmpty());
    }

    @Test
    void versionedMediaCannotBeReplacedAndUrlsRequireMembershipAndExpire() throws Exception {
        var owner = UlidCreator.getMonotonicUlid();
        var recipient = UlidCreator.getMonotonicUlid();
        var chat = create(owner, recipient);
        var grant = web.post()
                .uri("/api/chats/" + chat.chatId() + "/uploads")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .body(new UploadRequest(5, "text/plain"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(ObjectStorage.SignedUpload.class)
                .returnResult()
                .getResponseBody();
        String version = put(grant, "first");
        var command = new SendCommand(
                UlidCreator.getMonotonicUlid().toString(),
                chat.chatId(),
                null,
                new MediaReference(grant.uploadId(), version));
        StoredMessage stored;
        try (var sender = connect(owner, "browser");
                var receiver = connect(recipient, "phone")) {
            var accepted = sender.send(command).acceptance();
            stored = store.findMessage(
                            ChatModels.id(chat.chatId()), accepted.createdAt(), ChatModels.id(accepted.messageId()))
                    .orElseThrow();
            assertThat(receiver.awaitMessage().media().sizeBytes()).isEqualTo(5);
        }
        assertThat(stored.mediaVersionId()).isEqualTo(version);
        String replacement = put(grant, "other");
        assertThat(replacement).isNotEqualTo(version);
        var locator = new MessageLocator(stored.messageId().toString(), stored.createdAt());
        var url = download(token(recipient), chat.chatId(), locator);
        var response = http.send(
                HttpRequest.newBuilder(URI.create(url.url())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("first");
        web.post()
                .uri("/api/chats/" + chat.chatId() + "/media/download-url")
                .headers(headers -> headers.setBearerAuth(token(UlidCreator.getMonotonicUlid())))
                .body(locator)
                .exchange()
                .expectStatus()
                .isForbidden();
        Thread.sleep(2200);
        assertThat(http.send(
                                HttpRequest.newBuilder(URI.create(url.url()))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(403);
        assertThat(download(token(recipient), chat.chatId(), locator).expiresAt())
                .isAfter(url.expiresAt());
        var recipientCommand = new SendCommand(
                UlidCreator.getMonotonicUlid().toString(),
                chat.chatId(),
                null,
                new MediaReference(grant.uploadId(), version));
        assertThatThrownBy(() -> chats.send(recipient, recipientCommand)).isInstanceOf(ChatFailure.class);
        web.post()
                .uri("/api/chats/" + chat.chatId() + "/uploads")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .body(new UploadRequest(26214401, "text/plain"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private ObjectStorage.SignedDownload download(String jwt, String chatId, MessageLocator locator) {
        return web.post()
                .uri("/api/chats/" + chatId + "/media/download-url")
                .headers(headers -> headers.setBearerAuth(jwt))
                .body(locator)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ObjectStorage.SignedDownload.class)
                .returnResult()
                .getResponseBody();
    }

    private String put(ObjectStorage.SignedUpload grant, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(grant.url())).PUT(HttpRequest.BodyPublishers.ofString(body));
        grant.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("host")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
        return result.headers().firstValue("x-amz-version-id").orElseThrow();
    }
}
