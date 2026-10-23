package com.company.leave.identity;

import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.company.leave.identity.repo.EmployeeRepository;
import com.company.leave.identity.repo.RefreshTokenRepository;
import com.company.leave.identity.service.LeaveBalanceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared context for all integration tests (real PostgreSQL, schema identity_test). All tests extend this so
 * that only one application context - and therefore one clean/migrate - is created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
public abstract class AbstractIT {

    protected static final String PASSWORD = "Secret#1234";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected EmployeeRepository employees;
    @Autowired
    protected RefreshTokenRepository refreshTokens;
    @Autowired
    protected PasswordEncoder encoder;

    @MockitoBean
    protected LeaveBalanceClient leaveBalanceClient;

    protected Employee createUser(Role role, boolean active) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Employee e = new Employee();
        e.setEmployeeCode("T" + suffix);
        e.setName("Test " + suffix);
        e.setEmail("t" + suffix + "@example.com");
        e.setPasswordHash(encoder.encode(PASSWORD));
        e.setRole(role);
        e.setDepartment("Dept-" + suffix);
        e.setDesignation("Tester");
        e.setDateOfJoining(LocalDate.of(2022, 5, 2));
        e.setActive(active);
        return employees.save(e);
    }

    /** Each call comes from a fresh client IP so tests never trip the login rate limiter by accident. */
    protected ResultActions login(String identifier, String password) throws Exception {
        return loginFrom("10.9." + (IP_COUNTER.get() / 250) + "." + (IP_COUNTER.getAndIncrement() % 250 + 1),
                identifier, password);
    }

    protected ResultActions loginFrom(String ip, String identifier, String password) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password)));
        return mvc.perform(req);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String accessTokenFor(Employee e) throws Exception {
        return body(login(e.getEmployeeCode(), PASSWORD)).at("/data/accessToken").asText();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
