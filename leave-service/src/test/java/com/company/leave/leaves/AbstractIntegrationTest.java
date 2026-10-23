package com.company.leave.leaves;

import com.company.leave.common.security.AuthUser;
import com.company.leave.common.security.JwtService;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Base for tests that run against the real PostgreSQL schema {@code leave_test} (profile "test").
 * Identity-service is replaced by a mock {@link EmployeeDirectoryClient}. Tests should use
 * {@link #newEmployeeId()} so they never share balances/requests with each other.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestFlywayConfig.class)
public abstract class AbstractIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(10_000);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JwtService jwtService;

    @MockitoBean
    protected EmployeeDirectoryClient directory;

    protected static long newEmployeeId() {
        return IDS.incrementAndGet();
    }

    protected static AuthUser user(long id, String role) {
        String code = role.substring(0, Math.min(3, role.length())) + id;
        return new AuthUser(id, code, "User " + id, code.toLowerCase() + "@company.com", role, "Engineering");
    }

    /** Value of the Authorization header for the given user. */
    protected String bearer(AuthUser user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    protected static EmployeeDirectoryClient.EmployeeInfo info(AuthUser u, Long managerId) {
        return new EmployeeDirectoryClient.EmployeeInfo(u.id(), u.employeeCode(), u.name(), u.email(), u.role(),
                u.department(), managerId, true);
    }
}
