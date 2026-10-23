package com.company.leave.leaves.web;

import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.AbstractIntegrationTest;
import com.company.leave.leaves.domain.Notification;
import com.company.leave.leaves.repo.NotificationRepository;
import com.company.leave.leaves.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MasterDataApiTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notifications;
    @Autowired
    private NotificationRepository notificationRepo;

    private final AuthUser hr = user(newEmployeeId(), "HR");
    private final AuthUser emp = user(newEmployeeId(), "EMPLOYEE");

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                               AuthUser u, String body) throws Exception {
        return mvc.perform(b.header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void healthIsPublicAndWrongMethodIs405() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UP"));
        mvc.perform(post("/health")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void missingTokenGives401Json() throws Exception {
        mvc.perform(get("/api/v1/leave-types")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void leaveTypesListAndHrManagement() throws Exception {
        mvc.perform(get("/api/v1/leave-types").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(7)))
                .andExpect(jsonPath("$.data[?(@.code=='SL')].attachmentRequiredAfterDays").value(2));

        String body = "{\"name\":\"Study Leave\",\"code\":\"STD\",\"yearlyQuota\":5,\"carryForwardAllowed\":false}";
        json(post("/api/v1/leave-types"), emp, body).andExpect(status().isForbidden());
        json(post("/api/v1/leave-types"), hr, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("STD")).andExpect(jsonPath("$.data.paid").value(true));
        json(post("/api/v1/leave-types"), hr, body.replace("STD", "std")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_CODE"));
        json(post("/api/v1/leave-types"), hr, "{\"name\":\"\",\"code\":\"X\",\"yearlyQuota\":-1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void holidayCrudWithDuplicateDetection() throws Exception {
        String create = "{\"date\":\"2040-03-05\",\"name\":\"Founders Day\",\"type\":\"PUBLIC\"}";
        json(post("/api/v1/holidays"), emp, create).andExpect(status().isForbidden());
        ResultActions created = json(post("/api/v1/holidays"), hr, create).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.year").value(2040));
        String id = objectMapper.readTree(created.andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();
        json(post("/api/v1/holidays"), hr, create).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_HOLIDAY"));

        json(put("/api/v1/holidays/" + id), hr,
                "{\"date\":\"2040-03-06\",\"name\":\"Founders Day\",\"type\":\"OPTIONAL\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("OPTIONAL"));
        mvc.perform(get("/api/v1/holidays?year=2040").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].date").value("2040-03-06"));

        mvc.perform(delete("/api/v1/holidays/" + id).header("Authorization", bearer(emp)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/holidays/" + id).header("Authorization", bearer(hr)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(delete("/api/v1/holidays/" + id).header("Authorization", bearer(hr)))
                .andExpect(status().isNotFound());
    }

    @Test
    void balanceEndpointsAndAdjustment() throws Exception {
        mvc.perform(post("/internal/balances/init").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + emp.id() + ",\"dateOfJoining\":\"2020-01-02\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/balances/init").header("X-Internal-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + emp.id() + ",\"dateOfJoining\":\"2020-01-02\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/balances/init").header("X-Internal-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + emp.id() + ",\"dateOfJoining\":\"2020-01-02\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(7)));

        mvc.perform(get("/api/v1/leave-balances/me").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[?(@.code=='CL')].total").value(12.0));

        mvc.perform(get("/api/v1/employees/" + emp.id() + "/leave-balances").header("Authorization", bearer(emp)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/employees/" + emp.id() + "/leave-balances").header("Authorization", bearer(hr)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(7)));

        String adjust = "{\"leaveTypeId\":%d,\"days\":3,\"reason\":\"Comp off\"}";
        Long coId = jsonId("CO");
        json(post("/api/v1/employees/" + emp.id() + "/leave-balances/adjust"), emp, adjust.formatted(coId))
                .andExpect(status().isForbidden());
        json(post("/api/v1/employees/" + emp.id() + "/leave-balances/adjust"), hr, adjust.formatted(coId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3.0));
        json(post("/api/v1/employees/" + emp.id() + "/leave-balances/adjust"), hr,
                "{\"leaveTypeId\":" + coId + ",\"days\":3,\"reason\":\"\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEmployeeBalanceIs404() throws Exception {
        when(directory.get(999_999L)).thenThrow(com.company.leave.common.api.ApiException.notFound("Employee not found"));
        mvc.perform(get("/api/v1/employees/999999/leave-balances").header("Authorization", bearer(hr)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.message").value("Employee not found"));
    }

    @Test
    void notificationsListReadAndReadAll() throws Exception {
        for (int i = 0; i < 3; i++) {
            notifications.notify(emp.id(), "message " + i, null);
        }
        notifications.notify(hr.id(), "someone else's", null);

        mvc.perform(get("/api/v1/notifications?pageSize=2").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.meta.total").value(3)).andExpect(jsonPath("$.meta.pageSize").value(2))
                .andExpect(jsonPath("$.data[0].message").value("message 2"))
                .andExpect(jsonPath("$.data[0].read").value(false));
        mvc.perform(get("/api/v1/notifications?page=0").header("Authorization", bearer(emp)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications?pageSize=1000").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.pageSize").value(100));

        Notification foreign = notificationRepo.findByRecipientId(hr.id(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        mvc.perform(patch("/api/v1/notifications/" + foreign.getId() + "/read").header("Authorization", bearer(emp)))
                .andExpect(status().isNotFound());

        Notification own = notificationRepo.findByRecipientId(emp.id(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        mvc.perform(patch("/api/v1/notifications/" + own.getId() + "/read").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(get("/api/v1/notifications?unread=true").header("Authorization", bearer(emp)))
                .andExpect(jsonPath("$.meta.total").value(2));

        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", bearer(emp)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updated").value(2));
        mvc.perform(get("/api/v1/notifications?unread=true").header("Authorization", bearer(emp)))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    private Long jsonId(String code) throws Exception {
        String content = mvc.perform(get("/api/v1/leave-types").header("Authorization", bearer(hr)))
                .andReturn().getResponse().getContentAsString();
        for (var node : objectMapper.readTree(content).path("data")) {
            if (code.equals(node.path("code").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new IllegalStateException("leave type missing: " + code);
    }
}
