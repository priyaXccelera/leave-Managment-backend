package com.company.leave.identity;

import com.company.leave.common.api.ApiException;
import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmployeeAdminTest extends AbstractIT {

    private Employee hr;
    private String hrAuth;

    @BeforeEach
    void setUp() throws Exception {
        reset(leaveBalanceClient);
        hr = createUser(Role.HR, true);
        hrAuth = bearer(accessTokenFor(hr));
    }

    @AfterEach
    void resetMock() {
        reset(leaveBalanceClient);
    }

    private Map<String, Object> payload(String suffix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeCode", "N" + suffix);
        m.put("name", "New Person " + suffix);
        m.put("email", "New." + suffix + "@Example.com");
        m.put("password", "Welcome#123");
        m.put("role", "EMPLOYEE");
        m.put("department", "Dept-" + suffix);
        m.put("designation", "Analyst");
        m.put("dateOfJoining", "2024-03-04");
        return m;
    }

    private ResultActions create(String auth, Map<String, Object> payload) throws Exception {
        return mvc.perform(post("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void nonHrGets403OnEveryEmployeeEndpoint() throws Exception {
        String empAuth = bearer(accessTokenFor(createUser(Role.EMPLOYEE, true)));
        String mgrAuth = bearer(accessTokenFor(createUser(Role.MANAGER, true)));
        for (String auth : new String[]{empAuth, mgrAuth}) {
            mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
            mvc.perform(get("/api/v1/employees/" + hr.getId()).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            create(auth, payload(suffix())).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/employees/" + hr.getId()).header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/employees/" + hr.getId() + "/status").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void missingTokenGets401() throws Exception {
        mvc.perform(get("/api/v1/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/employees").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void hrCreatesEmployeeAndBalancesAreInitialised() throws Exception {
        String s = suffix();
        JsonNode created = body(create(hrAuth, payload(s))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.employeeCode").value("N" + s))
                .andExpect(jsonPath("$.data.email").value("new." + s + "@example.com"))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist()));
        long id = created.at("/data/id").asLong();
        verify(leaveBalanceClient).initBalances(eq(id), eq(LocalDate.of(2024, 3, 4)));

        // the new account can log in, case-insensitively by email
        login("NEW." + s + "@EXAMPLE.COM", "Welcome#123").andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees/" + id).header(HttpHeaders.AUTHORIZATION, hrAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Person " + s))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    @Test
    void createRollsBackWhenLeaveServiceFails() throws Exception {
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "down"))
                .when(leaveBalanceClient).initBalances(any(), any());
        String s = suffix();
        create(hrAuth, payload(s))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
        assertThat(employees.findByEmployeeCodeIgnoreCase("N" + s)).isEmpty();
    }

    @Test
    void duplicateCodeOrEmailGives409() throws Exception {
        String s = suffix();
        create(hrAuth, payload(s)).andExpect(status().isCreated());

        create(hrAuth, payload(s)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMPLOYEE"));

        Map<String, Object> sameCodeDifferentCase = payload(suffix());
        sameCodeDifferentCase.put("employeeCode", ("N" + s).toLowerCase());
        create(hrAuth, sameCodeDifferentCase).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMPLOYEE"))
                .andExpect(jsonPath("$.error.details[0].field").value("employeeCode"));

        Map<String, Object> sameEmail = payload(suffix());
        sameEmail.put("email", "NEW." + s + "@example.com");
        create(hrAuth, sameEmail).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void createValidation() throws Exception {
        Map<String, Object> badRole = payload(suffix());
        badRole.put("role", "BOSS");
        create(hrAuth, badRole).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("role"));

        Map<String, Object> badEmail = payload(suffix());
        badEmail.put("email", "not-an-email");
        create(hrAuth, badEmail).andExpect(status().isBadRequest());

        Map<String, Object> noPassword = payload(suffix());
        noPassword.remove("password");
        create(hrAuth, noPassword).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("password"));

        Map<String, Object> shortPassword = payload(suffix());
        shortPassword.put("password", "short");
        create(hrAuth, shortPassword).andExpect(status().isBadRequest());

        Map<String, Object> noDate = payload(suffix());
        noDate.remove("dateOfJoining");
        create(hrAuth, noDate).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void managerRulesOnCreate() throws Exception {
        Employee activeManager = createUser(Role.MANAGER, true);
        Employee inactiveManager = createUser(Role.MANAGER, false);
        Employee plainEmployee = createUser(Role.EMPLOYEE, true);

        Map<String, Object> ok = payload(suffix());
        ok.put("managerId", activeManager.getId());
        create(hrAuth, ok).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.managerId").value(activeManager.getId()))
                .andExpect(jsonPath("$.data.manager.employeeCode").value(activeManager.getEmployeeCode()));

        for (Long badManager : new Long[]{inactiveManager.getId(), plainEmployee.getId(), 999_999_999L}) {
            Map<String, Object> bad = payload(suffix());
            bad.put("managerId", badManager);
            create(hrAuth, bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("managerId"));
        }
    }

    @Test
    void unknownIdGives404() throws Exception {
        mvc.perform(get("/api/v1/employees/999999999").header(HttpHeaders.AUTHORIZATION, hrAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(put("/api/v1/employees/999999999").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(suffix()))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/employees/999999999/status").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ list

    @Test
    void listPaginatesFiltersSortsAndSearches() throws Exception {
        String tag = suffix();
        for (String n : new String[]{"a", "b", "c"}) {
            Map<String, Object> p = payload(tag + n);
            p.put("department", "Pagination-" + tag);
            p.put("name", "Zed-" + tag + "-" + n);
            create(hrAuth, p).andExpect(status().isCreated());
        }
        String dept = "Pagination-" + tag;

        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("department", dept.toLowerCase()).param("pageSize", "2").param("page", "1")
                        .param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.pageSize").value(2))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Zed-" + tag + "-c"))
                .andExpect(jsonPath("$.data[1].name").value("Zed-" + tag + "-b"));

        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("department", dept).param("pageSize", "2").param("page", "2").param("sort", "name,desc"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Zed-" + tag + "-a"));

        // search by name fragment (case-insensitive), by code and by email
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("q", "ZED-" + tag))
                .andExpect(jsonPath("$.meta.total").value(3));
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("q", "n" + tag + "b"))
                .andExpect(jsonPath("$.meta.total").value(1));
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("q", "NEW." + tag + "C@example"))
                .andExpect(jsonPath("$.meta.total").value(1));
        // LIKE wildcards are literal
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("q", "%"))
                .andExpect(jsonPath("$.meta.total").value(0));

        // role / active filters
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("department", dept).param("role", "MANAGER"))
                .andExpect(jsonPath("$.meta.total").value(0));
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("department", dept).param("role", "employee").param("active", "true"))
                .andExpect(jsonPath("$.meta.total").value(3));
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .param("department", dept).param("active", "false"))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void listParameterValidation() throws Exception {
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("pageSize", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("page", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("role", "BOSS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/employees").header(HttpHeaders.AUTHORIZATION, hrAuth).param("pageSize", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.pageSize").value(100));
    }

    // ------------------------------------------------------------------ update / status

    @Test
    void hrUpdatesEmployeeAndCanResetPassword() throws Exception {
        String s = suffix();
        long id = body(create(hrAuth, payload(s))).at("/data/id").asLong();
        String oldRefresh = body(login("N" + s, "Welcome#123")).at("/data/refreshToken").asText();

        Map<String, Object> upd = payload(s);
        upd.put("name", "Renamed " + s);
        upd.put("department", "Finance");
        upd.put("password", "Brand#New123");
        mvc.perform(put("/api/v1/employees/" + id).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed " + s))
                .andExpect(jsonPath("$.data.department").value("Finance"));

        login("N" + s, "Welcome#123").andExpect(status().isUnauthorized());
        login("N" + s, "Brand#New123").andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());

        // update without password keeps the existing one
        upd.remove("password");
        upd.put("designation", "Lead");
        mvc.perform(put("/api/v1/employees/" + id).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designation").value("Lead"));
        login("N" + s, "Brand#New123").andExpect(status().isOk());
    }

    @Test
    void updateRejectsDuplicatesSelfManagerAndCycles() throws Exception {
        String s1 = suffix();
        String s2 = suffix();
        long first = body(create(hrAuth, payload(s1))).at("/data/id").asLong();
        create(hrAuth, payload(s2)).andExpect(status().isCreated());

        Map<String, Object> clash = payload(s1);
        clash.put("employeeCode", "N" + s2);
        mvc.perform(put("/api/v1/employees/" + first).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(clash)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMPLOYEE"));

        // keeping own code/email is not a duplicate; but managerId == self is invalid
        Map<String, Object> self = payload(s1);
        self.put("role", "MANAGER");
        self.put("managerId", first);
        mvc.perform(put("/api/v1/employees/" + first).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(self)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("managerId"));

        // cycle: A manages B (both managers); making A report to B is rejected
        Employee a = createUser(Role.MANAGER, true);
        Employee b = createUser(Role.MANAGER, true);
        b.setManagerId(a.getId());
        employees.save(b);
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("employeeCode", a.getEmployeeCode());
        cycle.put("name", a.getName());
        cycle.put("email", a.getEmail());
        cycle.put("role", "MANAGER");
        cycle.put("department", a.getDepartment());
        cycle.put("dateOfJoining", "2022-05-02");
        cycle.put("managerId", b.getId());
        mvc.perform(put("/api/v1/employees/" + a.getId()).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cycle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("managerId"));
    }

    @Test
    void managerWithActiveReportsCannotBeDemoted() throws Exception {
        Employee manager = createUser(Role.MANAGER, true);
        Employee report = createUser(Role.EMPLOYEE, true);
        report.setManagerId(manager.getId());
        employees.save(report);

        Map<String, Object> demote = new LinkedHashMap<>();
        demote.put("employeeCode", manager.getEmployeeCode());
        demote.put("name", manager.getName());
        demote.put("email", manager.getEmail());
        demote.put("role", "EMPLOYEE");
        demote.put("department", manager.getDepartment());
        demote.put("dateOfJoining", "2022-05-02");
        mvc.perform(put("/api/v1/employees/" + manager.getId()).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(demote)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MANAGER_HAS_REPORTS"));

        // once the report is inactive the demotion goes through
        report.setActive(false);
        employees.save(report);
        mvc.perform(put("/api/v1/employees/" + manager.getId()).header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(demote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
    }

    @Test
    void statusChangeRevokesRefreshTokensAndBlocksLogin() throws Exception {
        Employee e = createUser(Role.EMPLOYEE, true);
        String refresh = body(login(e.getEmployeeCode(), PASSWORD)).at("/data/refreshToken").asText();

        mvc.perform(patch("/api/v1/employees/" + e.getId() + "/status").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        login(e.getEmployeeCode(), PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_INACTIVE"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().is4xxClientError());

        mvc.perform(patch("/api/v1/employees/" + e.getId() + "/status").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        login(e.getEmployeeCode(), PASSWORD).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/employees/" + e.getId() + "/status").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hrCannotDeactivateThemselves() throws Exception {
        mvc.perform(patch("/api/v1/employees/" + hr.getId() + "/status").header(HttpHeaders.AUTHORIZATION, hrAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CANNOT_DEACTIVATE_SELF"));
        assertThat(employees.findById(hr.getId()).orElseThrow().isActive()).isTrue();
    }
}
