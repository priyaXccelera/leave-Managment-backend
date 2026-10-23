package com.company.leave.identity;

import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalApiTest extends AbstractIT {

    private static final String KEY = "test-internal-key";

    @Test
    void requestsWithoutOrWithWrongKeyGet401Json() throws Exception {
        for (String path : new String[]{"/internal/employees/1", "/internal/employees/hr",
                "/internal/employees/by-manager/1", "/internal/employees?ids=1"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mvc.perform(get(path).header("X-Internal-Key", "wrong-key"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void jwtDoesNotGrantInternalAccess() throws Exception {
        Employee hr = createUser(Role.HR, true);
        mvc.perform(get("/internal/employees/hr").header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(hr))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalKeyDoesNotGrantPublicApiAccess() throws Exception {
        mvc.perform(get("/api/v1/employees").header("X-Internal-Key", KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getByIdReturnsInternalShape() throws Exception {
        Employee manager = createUser(Role.MANAGER, true);
        Employee e = createUser(Role.EMPLOYEE, true);
        e.setManagerId(manager.getId());
        employees.save(e);
        mvc.perform(get("/internal/employees/" + e.getId()).header("X-Internal-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(e.getId()))
                .andExpect(jsonPath("$.data.employeeCode").value(e.getEmployeeCode()))
                .andExpect(jsonPath("$.data.email").value(e.getEmail()))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.department").value(e.getDepartment()))
                .andExpect(jsonPath("$.data.managerId").value(manager.getId()))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/internal/employees/999999999").header("X-Internal-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void hrListingContainsOnlyActiveHr() throws Exception {
        Employee activeHr = createUser(Role.HR, true);
        Employee inactiveHr = createUser(Role.HR, false);
        Employee manager = createUser(Role.MANAGER, true);
        JsonNode data = body(mvc.perform(get("/internal/employees/hr").header("X-Internal-Key", KEY))
                .andExpect(status().isOk())).get("data");
        assertThat(data.isArray()).isTrue();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        data.forEach(n -> {
            ids.add(n.get("id").asLong());
            assertThat(n.get("role").asText()).isEqualTo("HR");
            assertThat(n.get("active").asBoolean()).isTrue();
        });
        assertThat(ids).contains(activeHr.getId()).doesNotContain(inactiveHr.getId(), manager.getId());
    }

    @Test
    void byManagerListsActiveDirectReports() throws Exception {
        Employee manager = createUser(Role.MANAGER, true);
        Employee r1 = createUser(Role.EMPLOYEE, true);
        Employee r2 = createUser(Role.EMPLOYEE, true);
        Employee gone = createUser(Role.EMPLOYEE, false);
        for (Employee r : new Employee[]{r1, r2, gone}) {
            r.setManagerId(manager.getId());
            employees.save(r);
        }
        mvc.perform(get("/internal/employees/by-manager/" + manager.getId()).header("X-Internal-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(r1.getId()))
                .andExpect(jsonPath("$.data[1].id").value(r2.getId()));
    }

    @Test
    void lookupByIdsAndDepartment() throws Exception {
        Employee a = createUser(Role.EMPLOYEE, true);
        Employee b = createUser(Role.EMPLOYEE, false);
        mvc.perform(get("/internal/employees").param("ids", a.getId() + "," + b.getId())
                        .header("X-Internal-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/internal/employees").param("department", a.getDepartment().toUpperCase())
                        .header("X-Internal-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(a.getId()));
        mvc.perform(get("/internal/employees").param("ids", "1,abc").header("X-Internal-Key", KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/employees").header("X-Internal-Key", KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericIdIsNotFoundNotServerError() throws Exception {
        mvc.perform(get("/internal/employees/abc").header("X-Internal-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
