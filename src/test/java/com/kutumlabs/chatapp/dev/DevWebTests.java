package com.kutumlabs.chatapp.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.kutumlabs.testfixture.DevWebHarness;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = DevWebHarness.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "spring.docker.compose.enabled=false")
@ActiveProfiles("dev")
class DevWebTests extends DevWebHarness {
    private static final int PORT = availablePort();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    private HttpResponse<String> request(String path, String body, String token)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + path));
        if (body != null)
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void issuesOnlyDemoIdentitiesAndValidatesRealSignaturesForProtectedApis()
            throws IOException, InterruptedException, ParseException {
        assertThat(request("/api/chats", null, null).statusCode()).isEqualTo(401);
        for (String identity : DevController.USERS.keySet()) {
            var response = request("/dev/token", "{\"identity\":\"" + identity + "\"}", null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            JsonNode body = JSON.readTree(response.body());
            String token = body.get("token").asString();
            var claims = SignedJWT.parse(token).getJWTClaimsSet();
            assertThat(claims.getStringClaim("user_id")).isEqualTo(DevController.USERS.get(identity));
            assertThat(Duration.between(
                            claims.getIssueTime().toInstant(),
                            claims.getExpirationTime().toInstant()))
                    .isEqualTo(Duration.ofHours(1));
            assertThat(request("/api/chats", null, token).statusCode()).isEqualTo(200);
            String[] parts = token.split("\\.");
            parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
            assertThat(request("/api/chats", null, String.join(".", parts)).statusCode())
                    .isEqualTo(401);
        }
        assertThat(request("/dev/token", "{\"identity\":\"admin\"}", null).statusCode())
                .isEqualTo(400);
        assertThat(request("/dev/token", "{}", null).statusCode()).isEqualTo(400);
        JsonNode jwk = JSON.readTree(request("/dev/jwks", null, null).body())
                .get("keys")
                .get(0);
        assertThat(jwk.has("d")).isFalse();
        assertThat(jwk.has("n")).isTrue();
    }

    @Test
    void servesPlaygroundAndDocumentsActualRestContracts() throws IOException, InterruptedException {
        assertThat(request("/dev/chat", null, null).body()).contains("Chat lab", "/dev/assets/chat.js");
        assertThat(request("/dev/assets/chat.js", null, null).statusCode()).isEqualTo(200);
        assertThat(request("/dev/protocol", null, null).body()).contains("/app/v1/message.send");
        assertThat(request("/swagger-ui/index.html", null, null).statusCode()).isEqualTo(200);
        var response = request("/v3/api-docs", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode spec = JSON.readTree(response.body());
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString())
                .isEqualTo("bearer");
        assertThat(spec.at("/security/0").has("bearerAuth")).isTrue();
        assertThat(spec.get("paths").size()).isEqualTo(8);
        spec.get("paths")
                .properties()
                .forEach(path -> path.getValue().properties().forEach(operation -> {
                    var codes = operation.getValue().get("responses").properties().stream()
                            .map(java.util.Map.Entry::getKey)
                            .toList();
                    assertThat(codes)
                            .as("%s %s success response", operation.getKey(), path.getKey())
                            .anyMatch(code -> code.startsWith("2"));
                }));
        assertThat(spec.at("/components/schemas/History/properties").has("nextCursor"))
                .isTrue();
        assertThat(spec.at("/components/schemas/MessageView/properties").has("messageId"))
                .isTrue();
        assertThat(spec.at("/components/schemas/ChatSummaryView/properties").has("chatId"))
                .isTrue();
        assertThat(spec.get("paths").has("/dev/token")).isFalse();
        assertThat(spec.at("/paths/~1api~1chats/post/responses").has("201")).isTrue();
        assertThat(spec.at("/paths/~1api~1chats~1{chatId}~1uploads/post/responses")
                        .has("201"))
                .isTrue();
        assertThat(spec.at("/paths/~1api~1chats/get/parameters").isMissingNode())
                .isTrue();
        assertThat(spec.at("/components/schemas/CreateChat/properties/members/example/0")
                        .asString())
                .isEqualTo(DevController.USERS.get("bob"));
        assertThat(spec.at("/paths/~1api~1chats~1{chatId}~1messages/get/description")
                        .asString())
                .contains("nextCursor", "limit");
        assertThat(spec.at("/paths/~1api~1chats/post/responses/401/content").isMissingNode())
                .isTrue();
    }

    @Test
    void preservesResponseContractsAndParameterDocumentationWithSharedMetadata()
            throws IOException, InterruptedException {
        JsonNode spec = JSON.readTree(request("/v3/api-docs", null, null).body());
        var expectedResponses = Map.of(
                "post /api/chats", "201 400 401 503",
                "get /api/chats", "200 401 503",
                "post /api/chats/{chatId}/read", "204 400 401 403 503",
                "put /api/devices/{deviceId}/push-token", "204 400 401 503",
                "get /api/chats/{chatId}/messages", "200 400 401 403 503",
                "post /api/chats/{chatId}/uploads", "201 400 401 403 503",
                "post /api/chats/{chatId}/media/download-url", "200 400 401 403 404 503",
                "get /api/connections", "200 401 503",
                "delete /api/connections/{sessionId}", "204 401 404 503");
        expectedResponses.forEach((endpoint, codes) -> {
            String[] parts = endpoint.split(" ", 2);
            JsonNode operation = spec.get("paths").get(parts[1]).get(parts[0]);
            var responses = operation.get("responses");
            assertThat(responses.properties().stream().map(Map.Entry::getKey).toList())
                    .as(endpoint)
                    .containsExactlyInAnyOrder(codes.split(" "));
            for (String code : codes.split(" ")) {
                JsonNode response = responses.get(code);
                if (code.startsWith("2")) {
                    if (code.equals("204")) {
                        assertThat(response.has("content")).as(endpoint).isFalse();
                    } else {
                        assertThat(response.get("content").properties())
                                .as(endpoint)
                                .isNotEmpty();
                        response.get("content").properties().forEach(media -> {
                            JsonNode schema = media.getValue().get("schema");
                            String ref = schema.path("$ref").asString();
                            if (ref.isEmpty()) ref = schema.at("/items/$ref").asString();
                            assertThat(ref).as(endpoint).startsWith("#/components/schemas/");
                            assertThat(spec.at(ref.substring(1)).isMissingNode())
                                    .as(endpoint)
                                    .isFalse();
                        });
                    }
                } else {
                    assertThat(response.get("$ref").asString()).isEqualTo("#/components/responses/Error" + code);
                    JsonNode shared = spec.at("/components/responses/Error" + code);
                    assertThat(shared.get("description").asString()).isNotBlank();
                    if (code.equals("401")) assertThat(shared.has("content")).isFalse();
                    else
                        assertThat(shared.at("/content/application~1json/schema/$ref")
                                        .asString())
                                .isEqualTo("#/components/schemas/ErrorBody");
                }
            }
            operation.path("parameters").forEach(parameter -> {
                assertThat(parameter.get("name").asString()).isNotIn("jwt", "authorization", "Authorization");
                if (parameter.get("name").asString().equals("chatId")) {
                    assertThat(parameter.get("description").asString()).isEqualTo("Chat ULID returned by create/list");
                    assertThat(parameter.get("example").asString()).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G5FAW");
                }
            });
        });
        assertThat(spec.at("/components/schemas/ErrorBody/properties").properties().stream()
                        .map(Map.Entry::getKey)
                        .toList())
                .containsExactlyInAnyOrder("code", "message", "retryable");
    }
}
