package com.company.leave.identity;

import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthPermissionTest extends AbstractIT {

    @Test
    void loginByEmployeeCodeReturnsTokensAndUser() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        login(e.getEmployeeCode(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.employeeCode").value(e.getEmployeeCode()))
                .andExpect(jsonPath("$.data.user.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.user.manager").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    void loginByEmailIsCaseInsensitive() throws Exception {
        Employee e = createUser(Role.MANAGER, true);
        login(e.getEmail().toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(e.getId()));
        login(e.getEmployeeCode().toLowerCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownUserGive401() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        login(e.getEmployeeCode(), "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        login("nobody-" + UUID.randomUUID(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void inactiveAccountCannotLogIn() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, false);
        login(e.getEmployeeCode(), PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void loginValidationAndMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.20.0.1").content("{\"identifier\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details").isArray());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.20.0.2").content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("MALFORMED_BODY"));
    }

    @Test
    void refreshRotatesAndOldTokenFails() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        String first = body(login(e.getEmployeeCode(), PASSWORD)).at("/data/refreshToken").asText();

        JsonNode rotated = body(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", first))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty()));
        String second = rotated.at("/data/refreshToken").asText();
        assertThat(second).isNotEqualTo(first);

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", first))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", second))))
                .andExpect(status().isOk());
    }

    @Test
    void refreshTokenIsStoredOnlyAsSha256Hash() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        String raw = body(login(e.getEmployeeCode(), PASSWORD)).at("/data/refreshToken").asText();
        assertThat(raw).hasSizeGreaterThanOrEqualTo(43);
        assertThat(refreshTokens.findByTokenHash(raw)).isEmpty();
        assertThat(refreshTokens.findAll()).anyMatch(t -> t.getEmployeeId().equals(e.getId())
                && t.getTokenHash().length() == 64 && !t.getTokenHash().equals(raw));
    }

    @Test
    void garbageRefreshTokenGives401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshRejectedForDeactivatedAccount() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        String refresh = body(login(e.getEmployeeCode(), PASSWORD)).at("/data/refreshToken").asText();
        e.setActive(false);
        employees.save(e);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void logoutRevokesPresentedRefreshToken() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        JsonNode tokens = body(login(e.getEmployeeCode(), PASSWORD)).at("/data");
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokens.get("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", tokens.get("refreshToken").asText()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", tokens.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutBodyRevokesAllCallerTokens() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        JsonNode a = body(login(e.getEmployeeCode(), PASSWORD)).at("/data");
        JsonNode b = body(login(e.getEmployeeCode(), PASSWORD)).at("/data");
        mvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(a.get("accessToken").asText())))
                .andExpect(status().isOk());
        for (JsonNode t : new JsonNode[]{a, b}) {
            mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", t.get("refreshToken").asText()))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void changePasswordFlow() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        JsonNode tokens = body(login(e.getEmployeeCode(), PASSWORD)).at("/data");
        String auth = bearer(tokens.get("accessToken").asText());

        // wrong current password
        mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"nope-nope\",\"newPassword\":\"Another#Pass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));
        // same password
        mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        // too short
        mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest());
        // success
        mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "Another#Pass1"))))
                .andExpect(status().isOk());

        login(e.getEmployeeCode(), PASSWORD).andExpect(status().isUnauthorized());
        login(e.getEmployeeCode(), "Another#Pass1").andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", tokens.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsProfileWithManager() throws Exception {
        Employee manager = createUser(Role.MANAGER, true);
        Employee e = createUser(Role.EMPLOYEE, true);
        e.setManagerId(manager.getId());
        employees.save(e);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(e))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(e.getId()))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.department").value(e.getDepartment()))
                .andExpect(jsonPath("$.data.manager.id").value(manager.getId()))
                .andExpect(jsonPath("$.data.manager.employeeCode").value(manager.getEmployeeCode()))
                .andExpect(jsonPath("$.data.manager.name").value(manager.getName()))
                .andExpect(jsonPath("$.data.dateOfJoining").value("2022-05-02"));
    }

    @Test
    void meWithoutOrWithBadTokenGives401Json() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRateLimitReturns429WithRetryAfter() throws Exception {
        String ip = "172.16.5." + (int) (Math.random() * 200 + 1);
        String id = "ratelimited-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            loginFrom(ip, id, "bad-password-" + i).andExpect(status().isUnauthorized());
        }
        loginFrom(ip, id.toUpperCase(), "bad-password-x")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        // other identifier from the same IP is a different key
        loginFrom(ip, "someone-else-" + UUID.randomUUID(), "x").andExpect(status().isUnauthorized());
        // same identifier from another IP is a different key
        loginFrom("172.17.5.9", id, "x").andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitAppliesEvenWhenPasswordIsCorrect() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        String ip = "172.18.7.7";
        for (int i = 0; i < 5; i++) {
            loginFrom(ip, e.getEmployeeCode(), PASSWORD).andExpect(status().isOk());
        }
        loginFrom(ip, e.getEmployeeCode(), PASSWORD).andExpect(status().isTooManyRequests());
    }
}
