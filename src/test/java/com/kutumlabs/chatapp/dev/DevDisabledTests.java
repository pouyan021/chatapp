package com.kutumlabs.chatapp.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.kutumlabs.testfixture.DevWebHarness;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = DevWebHarness.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.docker.compose.enabled=false")
class DevDisabledTests extends DevWebHarness {
    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void demoAndDocumentationAreUnavailableWithoutDevProfile() throws IOException, InterruptedException {
        assertThat(context.getBeansOfType(DevController.class)).isEmpty();
        assertThat(context.getBeansOfType(DevConfiguration.class)).isEmpty();
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[] {
                "/dev/chat",
                "/dev/assets/chat.js",
                "/dev/jwks",
                "/dev/protocol",
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs"
            }) {
                var response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isIn(401, 403, 404);
            }
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/dev/token"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"identity\":\"alice\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isIn(401, 403, 404);
        }
    }
}
