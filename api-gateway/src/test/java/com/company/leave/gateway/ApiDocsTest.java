package com.company.leave.gateway;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    private static StubBackend identity;
    private static StubBackend leave;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        identity = new StubBackend("identity");
        leave = new StubBackend("leave");
        registry.add("IDENTITY_SERVICE_URL", identity::url);
        registry.add("LEAVE_SERVICE_URL", leave::url);
    }

    @AfterAll
    static void stop() {
        identity.close();
        leave.close();
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void bind() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void swaggerUiIsServed() {
        client.get().uri("/docs").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(b -> org.assertj.core.api.Assertions.assertThat(b).contains("SwaggerUIBundle"));
    }

    @Test
    void swaggerAssetsAreServed() {
        client.get().uri("/webjars/swagger-ui/5.17.14/swagger-ui-bundle.js").exchange().expectStatus().isOk();
    }

    @Test
    void specServedAsYamlAndJson() {
        client.get().uri("/openapi.yaml").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.0.3")
                .jsonPath("$.paths['/api/v1/auth/login']").exists()
                .jsonPath("$.paths['/api/v1/leave-requests']").exists();
    }
}
