package com.company.leave.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static StubBackend identity;
    private static StubBackend leave;

    @BeforeAll
    static void startStubs() throws IOException {
        identity = new StubBackend("identity");
        leave = new StubBackend("leave");
    }

    @AfterAll
    static void stopStubs() {
        identity.close();
        leave.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        // started here because @DynamicPropertySource runs before @BeforeAll
        if (identity == null) {
            identity = new StubBackend("identity");
        }
        if (leave == null) {
            leave = new StubBackend("leave");
        }
        registry.add("IDENTITY_SERVICE_URL", identity::url);
        registry.add("LEAVE_SERVICE_URL", leave::url);
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
    void healthIsUp() {
        client.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("UP");
    }

    @Test
    void postHealthIs405WithAllowHeader() {
        client.post().uri("/health").exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals(HttpHeaders.ALLOW, "GET")
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("METHOD_NOT_ALLOWED")
                .jsonPath("$.error.details").isArray();
    }

    @Test
    void unknownRouteIs404JsonForAnyMethod() {
        for (HttpMethod m : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE, HttpMethod.PUT}) {
            client.method(m).uri("/api/v1/nothing-here").exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$.success").isEqualTo(false)
                    .jsonPath("$.error.code").isEqualTo("NOT_FOUND")
                    .jsonPath("$.error.message").isNotEmpty()
                    .jsonPath("$.error.details").isArray();
        }
    }

    @Test
    void internalPathsAreNotRouted() {
        client.get().uri("/internal/employees").header("X-Internal-Key", "k").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error.code").isEqualTo("NOT_FOUND");
        client.get().uri("/api/v1/internal/employees/1").exchange().expectStatus().isNotFound();
    }

    @Test
    void authGoesToIdentityAndPreservesAuthorizationAndBody() {
        client.post().uri("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer abc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"identifier\":\"a\",\"password\":\"b\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.backend").isEqualTo("identity")
                .jsonPath("$.method").isEqualTo("POST")
                .jsonPath("$.path").isEqualTo("/api/v1/auth/login")
                .jsonPath("$.auth").isEqualTo("Bearer abc")
                .jsonPath("$.bodyLength").isEqualTo(33);
    }

    @Test
    void employeesRootAndSubpathsGoToIdentity() {
        client.get().uri("/api/v1/employees").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("identity");
        client.get().uri("/api/v1/employees?page=2&pageSize=5").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.backend").isEqualTo("identity")
                .jsonPath("$.query").isEqualTo("page=2&pageSize=5");
        client.get().uri("/api/v1/employees/5").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("identity");
        client.get().uri("/api/v1/employees/5/leave-types").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("identity");
    }

    @Test
    void employeeLeaveBalancesGoToLeaveService() {
        client.get().uri("/api/v1/employees/5/leave-balances").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.backend").isEqualTo("leave")
                .jsonPath("$.path").isEqualTo("/api/v1/employees/5/leave-balances");
        client.put().uri("/api/v1/employees/5/leave-balances/3").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("leave");
    }

    @Test
    void leaveServicePathsIncludingBarePaths() {
        String[] paths = {
            "/api/v1/leave-requests", "/api/v1/leave-requests/me", "/api/v1/leave-balances/me",
            "/api/v1/leave-balances", "/api/v1/leave-types", "/api/v1/holidays", "/api/v1/holidays/1",
            "/api/v1/reports/summary", "/api/v1/notifications", "/api/v1/notifications/3/read",
            "/api/v1/dashboard", "/api/v1/dashboard/summary", "/api/v1/team", "/api/v1/team/calendar"
        };
        for (String p : paths) {
            client.get().uri(p).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.backend").isEqualTo("leave")
                    .jsonPath("$.path").isEqualTo(p);
        }
    }

    @Test
    void largeBodyIsForwarded() {
        byte[] big = new byte[12 * 1024 * 1024];
        client.post().uri("/api/v1/leave-requests")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(big)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.backend").isEqualTo("leave")
                .jsonPath("$.bodyLength").isEqualTo(big.length);
    }

    @Test
    void downstreamHeadersAndStatusPassThrough() {
        client.get().uri("/api/v1/auth/boom405").exchange()
                .expectHeader().valueEquals("Allow", "GET, POST")
                .expectHeader().valueEquals("Retry-After", "7")
                .expectHeader().valueEquals("Content-Disposition", "attachment; filename=\"x.csv\"");
        client.get().uri("/api/v1/auth/boom405").exchange().expectStatus().isEqualTo(405);
    }

    @Test
    void corsPreflightReturnsAllowOrigin() {
        client.options().uri("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://example.com")
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }

    @Test
    void duplicatedCorsHeadersFromDownstreamAreDeduped() {
        client.get().uri("/api/v1/auth/me")
                .header(HttpHeaders.ORIGIN, "http://example.com")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        v -> assertThat(v).isEqualTo("http://example.com"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        v -> assertThat(v).contains("Content-Disposition", "Retry-After", "Allow"));
        client.get().uri("/api/v1/auth/me")
                .header(HttpHeaders.ORIGIN, "http://example.com")
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders()
                .get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .stream().peek(v -> assertThat(v).isNotBlank()).toList()
                .forEach(v -> assertThat(v).isEqualTo("http://example.com"));
    }
}
