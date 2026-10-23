package com.company.leave.gateway;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDownstreamDownTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + deadPort());
        registry.add("LEAVE_SERVICE_URL", () -> "http://127.0.0.1:" + deadPort());
    }

    private static int deadPort() {
        try {
            return StubBackend.closedPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    @Test
    void identityDownGives503Envelope() {
        client.get().uri("/api/v1/auth/me").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.error.details").isArray();
    }

    @Test
    void leaveDownGives503EnvelopeWithoutStackTrace() {
        client.get().uri("/api/v1/leave-requests/me").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.error.message").value(m -> {
                    String s = String.valueOf(m);
                    if (s.contains("Exception") || s.contains("\tat ")) {
                        throw new AssertionError("message leaks internals: " + s);
                    }
                });
    }

    @Test
    void healthStillUpWhenDownstreamsDown() {
        client.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("UP");
    }
}
