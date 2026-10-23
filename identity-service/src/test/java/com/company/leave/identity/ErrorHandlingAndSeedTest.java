package com.company.leave.identity;

import com.company.leave.identity.config.DataSeeder;
import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorHandlingAndSeedTest extends AbstractIT {

    @Autowired
    private DataSeeder seeder;

    @Test
    void healthEndpointsArePublic() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void wrongMethodGives405WithAllowHeader() throws Exception {
        mvc.perform(post("/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(delete("/api/v1/auth/refresh"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists(HttpHeaders.ALLOW));
    }

    @Test
    void unknownRouteGives404JsonWhenAuthenticatedAnd401Otherwise() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(e))))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("json")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void malformedAndEmptyBodiesGive400() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_BODY"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.50.0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void seederCreatedTheDemoAccounts() throws Exception {
        for (String code : new String[]{"HR001", "MGR001", "MGR002", "EMP001", "EMP002", "EMP003", "EMP004", "EMP005"}) {
            assertThat(employees.findByEmployeeCodeIgnoreCase(code)).as(code).isPresent();
        }
        Employee emp4 = employees.findByEmployeeCodeIgnoreCase("EMP004").orElseThrow();
        Employee mgr2 = employees.findByEmployeeCodeIgnoreCase("MGR002").orElseThrow();
        Employee emp1 = employees.findByEmployeeCodeIgnoreCase("EMP001").orElseThrow();
        Employee mgr1 = employees.findByEmployeeCodeIgnoreCase("MGR001").orElseThrow();
        assertThat(emp4.getManagerId()).isEqualTo(mgr2.getId());
        assertThat(emp1.getManagerId()).isEqualTo(mgr1.getId());
        assertThat(employees.findByEmployeeCodeIgnoreCase("HR001").orElseThrow().getRole()).isEqualTo(Role.HR);

        login("hr@company.com", "Password@123").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("HR"));
        login("EMP005", "Password@123").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.manager.employeeCode").value("MGR002"));
    }

    @Test
    void seedingIsIdempotentAndInitialisesBalancesInBackground() {
        long before = employees.count();
        seeder.run(null);
        seeder.run(null);
        assertThat(employees.count()).isEqualTo(before);
        Long hrId = employees.findByEmployeeCodeIgnoreCase("HR001").orElseThrow().getId();
        verify(leaveBalanceClient, timeout(5000).atLeastOnce()).initBalances(eq(hrId), any());
    }
}
