package com.company.leave.leaves.web;

import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.WorkflowTestSupport;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PermissionTest extends WorkflowTestSupport {

    private static final byte[] PDF = "%PDF-1.4 certificate".getBytes(StandardCharsets.UTF_8);
    private static final String LEAVE_REQUESTS = "/api/v1/leave-requests";

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, AuthUser u) {
        return b.header(HttpHeaders.AUTHORIZATION, bearer(u));
    }

    private ResultActions send(MockHttpServletRequestBuilder b, AuthUser u) throws Exception {
        return mvc.perform(as(b, u));
    }

    private ResultActions postJson(String url, AuthUser u, String body) throws Exception {
        return mvc.perform(as(post(url), u).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String applyBody(Long typeId, LocalDate from, LocalDate to) {
        return "{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"reason\":\"Family event\"}"
                .formatted(typeId, from, to);
    }

    private long applyViaApi(AuthUser u, String type, LocalDate from, LocalDate to) throws Exception {
        MvcResult r = postJson(LEAVE_REQUESTS, u, applyBody(typeId(type), from, to))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void missingOrInvalidTokenGives401Json() throws Exception {
        mvc.perform(get(LEAVE_REQUESTS + "/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post(LEAVE_REQUESTS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/team/calendar?from=2030-01-01&to=2030-01-02")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/summary?groupBy=month")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRouteIs404JsonAndWrongMethodIs405WithAllow() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        send(get("/api/v1/does-not-exist"), emp).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        send(get(LEAVE_REQUESTS + "/abc"), emp).andExpect(status().isNotFound());
        send(delete(LEAVE_REQUESTS + "/1"), emp).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists(HttpHeaders.ALLOW)).andExpect(jsonPath("$.success").value(false));
        send(delete("/api/v1/dashboard"), emp).andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void readingOtherPeoplesRequestsIsRestricted() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser otherMgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser colleague = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        long id = applyViaApi(emp, "CL", nextMonday(14), nextMonday(14));

        send(get(LEAVE_REQUESTS + "/" + id), emp).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.history", hasSize(1)));
        send(get(LEAVE_REQUESTS + "/" + id), mgr).andExpect(status().isOk());
        send(get(LEAVE_REQUESTS + "/" + id), hr).andExpect(status().isOk());
        send(get(LEAVE_REQUESTS + "/" + id), colleague).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        send(get(LEAVE_REQUESTS + "/" + id), otherMgr).andExpect(status().isForbidden());
        send(get(LEAVE_REQUESTS + "/987654321"), hr).andExpect(status().isNotFound());
        send(post(LEAVE_REQUESTS + "/" + id + "/cancel"), colleague).andExpect(status().isForbidden());
    }

    @Test
    void actionEndpointsEnforceRoles() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser otherMgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        long id = applyViaApi(emp, "CL", nextMonday(14), nextMonday(14));
        String approve = "{\"action\":\"APPROVE\"}";

        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", emp, approve).andExpect(status().isForbidden());
        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", emp, approve).andExpect(status().isForbidden());
        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", mgr, approve).andExpect(status().isForbidden());
        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", hr, approve).andExpect(status().isForbidden());
        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", otherMgr, approve).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", hr, approve).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", mgr, "{\"action\":\"REJECT\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", mgr, "{\"comment\":\"x\"}")
                .andExpect(status().isBadRequest());

        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", mgr, "{\"action\":\"APPROVE\",\"comment\":\"fine\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_HR"))
                .andExpect(jsonPath("$.data.currentStage").value("HR"));
        postJson(LEAVE_REQUESTS + "/" + id + "/manager-action", mgr, approve).andExpect(status().isConflict());
        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", hr, approve).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.history", hasSize(3)));
    }

    @Test
    void hrSelfApprovalIsRejectedAndAnotherHrCanApprove() throws Exception {
        AuthUser hr = register("HR", null);
        AuthUser hr2 = register("HR", null);
        registerHrUsers(hr, hr2);
        long id = applyViaApi(hr, "CL", nextMonday(14), nextMonday(14));

        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", hr, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("HR cannot act on their own leave request"));

        send(get(LEAVE_REQUESTS + "/pending-for-hr?sort=id,desc"), hr)
                .andExpect(jsonPath("$.data[*].id", not(hasItem((int) id))));
        send(get(LEAVE_REQUESTS + "/pending-for-hr?sort=id,desc"), hr2)
                .andExpect(jsonPath("$.data[*].id", hasItem((int) id)));
        postJson(LEAVE_REQUESTS + "/" + id + "/hr-action", hr2, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void nonHrUsersCannotUseHrEndpoints() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        AuthUser mgr = register("MANAGER", null);
        for (AuthUser u : new AuthUser[] {emp, mgr}) {
            send(get("/api/v1/reports/summary?groupBy=month"), u).andExpect(status().isForbidden());
            send(get("/api/v1/reports/on-leave"), u).andExpect(status().isForbidden());
            send(get("/api/v1/reports/export"), u).andExpect(status().isForbidden());
            send(get(LEAVE_REQUESTS), u).andExpect(status().isForbidden());
            send(get(LEAVE_REQUESTS + "/pending-for-hr"), u).andExpect(status().isForbidden());
            postJson("/api/v1/leave-types", u, "{\"name\":\"X\",\"code\":\"XX\"}").andExpect(status().isForbidden());
            postJson("/api/v1/holidays", u, "{\"date\":\"2041-05-05\",\"name\":\"X\"}")
                    .andExpect(status().isForbidden());
            postJson("/api/v1/employees/" + emp.id() + "/leave-balances/adjust", u,
                    "{\"leaveTypeId\":1,\"days\":1,\"reason\":\"x\"}").andExpect(status().isForbidden());
        }
        send(get(LEAVE_REQUESTS + "/pending-for-manager"), emp).andExpect(status().isForbidden());
        send(get(LEAVE_REQUESTS + "/team"), emp).andExpect(status().isForbidden());
        send(get("/api/v1/team/calendar?from=2030-01-01&to=2030-01-31"), emp).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- lists

    @Test
    void managerSeesOnlyDirectReportsAndStaticPathsAreNotCapturedByIdRoute() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser mgr2 = register("MANAGER", null);
        AuthUser e1 = register("EMPLOYEE", mgr.id());
        AuthUser e2 = register("EMPLOYEE", mgr.id());
        AuthUser e3 = register("EMPLOYEE", mgr2.id());
        registerHrUsers(register("HR", null));
        LocalDate d = nextMonday(14);
        long r1 = applyViaApi(e1, "CL", d, d);
        long r2 = applyViaApi(e2, "CL", d, d);
        long r3 = applyViaApi(e3, "CL", d, d);
        postJson(LEAVE_REQUESTS + "/" + r2 + "/manager-action", mgr, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());

        send(get(LEAVE_REQUESTS + "/pending-for-manager"), mgr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(r1))
                .andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.meta.page").value(1));
        send(get(LEAVE_REQUESTS + "/team?sort=id,asc"), mgr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2))).andExpect(jsonPath("$.data[0].id").value(r1))
                .andExpect(jsonPath("$.data[1].id").value(r2)).andExpect(jsonPath("$.meta.total").value(2));
        send(get(LEAVE_REQUESTS + "/team?status=PENDING_HR"), mgr)
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(r2));
        send(get(LEAVE_REQUESTS + "/team"), mgr2).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(r3));
        send(get(LEAVE_REQUESTS + "/me"), e1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].employee.id").value(e1.id()));
        send(get(LEAVE_REQUESTS + "/me"), mgr).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void listFiltersPagingAndSortingAreAppliedInTheDatabase() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate d = nextMonday(14);
        long first = applyViaApi(emp, "CL", d, d);
        long second = applyViaApi(emp, "SL", d.plusDays(7), d.plusDays(8));

        send(get(LEAVE_REQUESTS + "/me?pageSize=1&sort=fromDate,desc"), emp).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.meta.total").value(2)).andExpect(jsonPath("$.meta.pageSize").value(1));
        send(get(LEAVE_REQUESTS + "/me?pageSize=1&page=2&sort=fromDate,desc"), emp)
                .andExpect(jsonPath("$.data[0].id").value(first)).andExpect(jsonPath("$.meta.page").value(2));
        send(get(LEAVE_REQUESTS + "/me?pageSize=500"), emp).andExpect(jsonPath("$.meta.pageSize").value(100));
        send(get(LEAVE_REQUESTS + "/me?leaveTypeId=" + typeId("SL")), emp)
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(second));
        send(get(LEAVE_REQUESTS + "/me?from=" + d.plusDays(1) + "&to=" + d.plusDays(7)), emp)
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(second));
        send(get(LEAVE_REQUESTS + "/me?status=APPROVED"), emp).andExpect(jsonPath("$.meta.total").value(0));

        send(get(LEAVE_REQUESTS + "/me?sort=password,asc"), emp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        send(get(LEAVE_REQUESTS + "/me?status=BOGUS"), emp).andExpect(status().isBadRequest());
        send(get(LEAVE_REQUESTS + "/me?page=0"), emp).andExpect(status().isBadRequest());
        send(get(LEAVE_REQUESTS + "/me?pageSize=0"), emp).andExpect(status().isBadRequest());
        send(get(LEAVE_REQUESTS + "/me?from=nonsense"), emp).andExpect(status().isBadRequest());
        send(get(LEAVE_REQUESTS + "/me?from=2030-02-01&to=2030-01-01"), emp).andExpect(status().isBadRequest());

        send(get(LEAVE_REQUESTS + "?employeeId=" + emp.id() + "&status=PENDING_HR"), hr)
                .andExpect(jsonPath("$.meta.total").value(2));
        send(get(LEAVE_REQUESTS + "?employeeId=" + emp.id() + "&department=Engineering&sort=employeeName,asc"), hr)
                .andExpect(jsonPath("$.meta.total").value(2));
        send(get(LEAVE_REQUESTS + "?employeeId=" + emp.id() + "&department=Nowhere"), hr)
                .andExpect(jsonPath("$.meta.total").value(0));
        // overlap semantics: a leave ending on 'from' still matches, one ending before it does not
        send(get(LEAVE_REQUESTS + "?employeeId=" + emp.id() + "&from=" + d + "&to=" + d), hr)
                .andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].id").value(first));
        send(get(LEAVE_REQUESTS + "?employeeId=" + emp.id() + "&from=" + d.plusDays(1) + "&to=" + d.plusDays(6)), hr)
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void leaveTypeAndHolidayListsArePaged() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        AuthUser hr = register("HR", null);
        send(get("/api/v1/leave-types?pageSize=2&sort=name,asc"), emp).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2))).andExpect(jsonPath("$.meta.pageSize").value(2))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(7)));
        send(get("/api/v1/leave-types?sort=secret"), emp).andExpect(status().isBadRequest());
        send(get("/api/v1/leave-types"), emp).andExpect(jsonPath("$.meta.pageSize").value(50));

        postJson("/api/v1/holidays", hr, "{\"date\":\"2041-02-01\",\"name\":\"B day\"}").andExpect(status().isCreated());
        postJson("/api/v1/holidays", hr, "{\"date\":\"2041-01-01\",\"name\":\"A day\"}").andExpect(status().isCreated());
        send(get("/api/v1/holidays?year=2041&pageSize=1"), emp).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].date").value("2041-01-01"))
                .andExpect(jsonPath("$.meta.total").value(2));
        send(get("/api/v1/holidays?year=2041&pageSize=1&page=2"), emp)
                .andExpect(jsonPath("$.data[0].date").value("2041-02-01"));
        send(get("/api/v1/holidays?year=2041&sort=date,desc"), emp).andExpect(jsonPath("$.data[0].date").value("2041-02-01"));
        send(get("/api/v1/holidays?sort=bogus"), emp).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- apply

    @Test
    void applyViaJsonReturns201WithEnvelope() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        registerHrUsers(register("HR", null));
        LocalDate d = nextMonday(14);

        postJson(LEAVE_REQUESTS, emp, applyBody(typeId("CL"), d, d.plusDays(1))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_MANAGER"))
                .andExpect(jsonPath("$.data.currentStage").value("MANAGER"))
                .andExpect(jsonPath("$.data.workingDays").value(2.0))
                .andExpect(jsonPath("$.data.fromDate").value(d.toString()))
                .andExpect(jsonPath("$.data.employee.id").value(emp.id()))
                .andExpect(jsonPath("$.data.employee.employeeCode").value(emp.employeeCode()))
                .andExpect(jsonPath("$.data.leaveType.code").value("CL"))
                .andExpect(jsonPath("$.data.history[0].action").value("APPLIED"))
                .andExpect(jsonPath("$.data.appliedOn", startsWith(LocalDate.now().toString().substring(0, 4))))
                .andExpect(jsonPath("$.data.attachmentUrl").doesNotExist());
    }

    @Test
    void applyValidationAndBusinessErrorsUseTheErrorEnvelope() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate d = nextMonday(14);

        postJson(LEAVE_REQUESTS, emp, "{\"leaveTypeId\":" + typeId("CL") + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        postJson(LEAVE_REQUESTS, emp, "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        postJson(LEAVE_REQUESTS, emp, applyBody(typeId("CL"), d.plusDays(2), d)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        postJson(LEAVE_REQUESTS, emp, applyBody(typeId("SL"), d, d.plusDays(3))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_REQUIRED"));
        postJson(LEAVE_REQUESTS, emp, applyBody(typeId("CL"), d, d.plusDays(25))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_BALANCE"));
        applyViaApi(emp, "CL", d, d);
        postJson(LEAVE_REQUESTS, emp, applyBody(typeId("CL"), d, d)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LEAVE_OVERLAP"));
        mvc.perform(as(post(LEAVE_REQUESTS), emp).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void previewEndpoint() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        LocalDate d = nextMonday(14);
        postJson(LEAVE_REQUESTS + "/preview", emp,
                "{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\"}".formatted(typeId("CL"), d, d.plusDays(7)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workingDays").value(6.0))
                .andExpect(jsonPath("$.data.isValid").value(true))
                .andExpect(jsonPath("$.data.excludedDates", hasSize(2)))
                .andExpect(jsonPath("$.data.availableBalance").value(12.0))
                .andExpect(jsonPath("$.data.warnings", hasSize(0)));
        postJson(LEAVE_REQUESTS + "/preview", emp,
                "{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\"}".formatted(typeId("SL"), d, d.plusDays(3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isValid").value(false))
                .andExpect(jsonPath("$.data.warnings[0]").value("Attachment is required for this leave"));
        postJson(LEAVE_REQUESTS + "/preview", emp, "{\"fromDate\":\"" + d + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void multipartApplyWithAttachmentAndDownloadPermissions() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser stranger = register("EMPLOYEE", mgr.id());
        AuthUser otherMgr = register("MANAGER", null);
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate d = nextMonday(14);
        MockMultipartFile file = new MockMultipartFile("attachment", "../../certificate.pdf", "application/pdf", PDF);

        // sick leave of 4 days needs the document; flat form fields
        mvc.perform(as(multipart(LEAVE_REQUESTS), emp).param("leaveTypeId", typeId("SL").toString())
                        .param("fromDate", d.toString()).param("toDate", d.plusDays(3).toString())
                        .param("reason", "Fever").param("halfDay", "false"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ATTACHMENT_REQUIRED"));
        MvcResult created = mvc.perform(as(multipart(LEAVE_REQUESTS).file(file), emp)
                        .param("leaveTypeId", typeId("SL").toString()).param("fromDate", d.toString())
                        .param("toDate", d.plusDays(3).toString()).param("reason", "Fever"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING_MANAGER"))
                .andExpect(jsonPath("$.data.attachmentUrl", containsString("/attachment"))).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        String url = LEAVE_REQUESTS + "/" + id + "/attachment";

        for (AuthUser allowed : new AuthUser[] {emp, mgr, hr}) {
            MvcResult download = send(get(url), allowed).andExpect(status().isOk())
                    .andExpect(content().contentType("application/pdf"))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                    .andReturn();
            assertArrayEquals(PDF, download.getResponse().getContentAsByteArray());
        }
        send(get(url), stranger).andExpect(status().isForbidden());
        send(get(url), otherMgr).andExpect(status().isForbidden());
        send(get(LEAVE_REQUESTS + "/" + id), stranger).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());

        // JSON part named "data" + file part
        MockMultipartFile data = new MockMultipartFile("data", "", "application/json",
                applyBody(typeId("SL"), d.plusDays(7), d.plusDays(10)).getBytes(StandardCharsets.UTF_8));
        mvc.perform(as(multipart(LEAVE_REQUESTS).file(data).file(file), emp)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workingDays").value(4.0));

        // a request without a document has nothing to download
        long plain = applyViaApi(emp, "CL", d.plusDays(14), d.plusDays(14));
        send(get(LEAVE_REQUESTS + "/" + plain + "/attachment"), emp).andExpect(status().isNotFound());
    }

    @Test
    void invalidAttachmentsAreRejected() throws Exception {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate d = nextMonday(14);
        mvc.perform(as(multipart(LEAVE_REQUESTS)
                        .file(new MockMultipartFile("attachment", "run.exe", "application/octet-stream", PDF))
                        .param("leaveTypeId", typeId("CL").toString()).param("fromDate", d.toString())
                        .param("toDate", d.toString()).param("reason", "x"), emp))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(as(multipart(LEAVE_REQUESTS)
                        .file(new MockMultipartFile("attachment", "fake.pdf", "application/pdf", "MZ not a pdf".getBytes()))
                        .param("leaveTypeId", typeId("CL").toString()).param("fromDate", d.toString())
                        .param("toDate", d.toString()).param("reason", "x"), emp))
                .andExpect(status().isBadRequest());
        mvc.perform(as(multipart(LEAVE_REQUESTS)
                        .file(new MockMultipartFile("attachment", "empty.pdf", "application/pdf", new byte[0]))
                        .param("leaveTypeId", typeId("CL").toString()).param("fromDate", d.toString())
                        .param("toDate", d.toString()).param("reason", "x"), emp))
                .andExpect(status().isBadRequest());
        mvc.perform(as(multipart(LEAVE_REQUESTS).param("leaveTypeId", "abc"), emp)).andExpect(status().isBadRequest());
        mvc.perform(as(multipart(LEAVE_REQUESTS).param("fromDate", "31-12-2030"), emp))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelEndpoint() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        registerHrUsers(register("HR", null));
        long id = applyViaApi(emp, "CL", nextMonday(14), nextMonday(14));
        postJson(LEAVE_REQUESTS + "/" + id + "/cancel", emp, "{\"comment\":\"change of plans\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.currentStage").value("NONE"));
        send(post(LEAVE_REQUESTS + "/" + id + "/cancel"), emp).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        long second = applyViaApi(emp, "CL", nextMonday(14), nextMonday(14));
        send(post(LEAVE_REQUESTS + "/" + second + "/cancel"), emp).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- calendar, dashboard, reports

    @Test
    void teamCalendar() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser foreign = register("EMPLOYEE", register("MANAGER", null).id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        registerReports(mgr, emp);
        LocalDate d = nextMonday(14);
        long mine = applyViaApi(emp, "CL", d, d.plusDays(1));
        applyViaApi(foreign, "CL", d, d.plusDays(1));
        long own = applyViaApi(mgr, "EL", d.plusDays(7), d.plusDays(7));
        String range = "?from=" + d + "&to=" + d.plusDays(10);

        send(get("/api/v1/team/calendar" + range), mgr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.requestId==" + mine + ")].leaveType").value("Casual Leave"))
                .andExpect(jsonPath("$.data[?(@.requestId==" + mine + ")].status").value("PENDING_MANAGER"))
                .andExpect(jsonPath("$.data[*].requestId", hasItem((int) own)));
        send(get("/api/v1/team/calendar" + range), hr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(3)));
        send(get("/api/v1/team/calendar" + range + "&department=Nowhere"), hr).andExpect(jsonPath("$.data", hasSize(0)));
        send(get("/api/v1/team/calendar?from=" + d.plusDays(2) + "&to=" + d), hr).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/team/calendar?from=" + d + "&to=" + d.plusDays(400)), hr).andExpect(status().isBadRequest());
        send(get("/api/v1/team/calendar?from=bad&to=" + d), hr).andExpect(status().isBadRequest());
        send(get("/api/v1/team/calendar?to=" + d), hr).andExpect(status().isBadRequest());
        send(get("/api/v1/team/calendar" + range), emp).andExpect(status().isForbidden());
    }

    @Test
    void dashboardPerRole() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        registerReports(mgr, emp);
        LocalDate d = nextMonday(14);
        applyViaApi(emp, "CL", d, d);
        insertRequest(emp, mgr.id(), "SL", today(), today(), RequestStatus.APPROVED);

        send(get("/api/v1/dashboard"), emp).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE")).andExpect(jsonPath("$.data.pendingApprovals").value(0))
                .andExpect(jsonPath("$.data.balances", hasSize(1)))
                .andExpect(jsonPath("$.data.upcomingLeaves", hasSize(2)))
                .andExpect(jsonPath("$.data.upcomingLeaves[0].fromDate").value(today().toString()))
                .andExpect(jsonPath("$.data.upcomingHolidays").isArray())
                .andExpect(jsonPath("$.data.unreadNotifications").value(0))
                .andExpect(jsonPath("$.data.onLeaveToday", hasSize(0)));
        send(get("/api/v1/dashboard"), mgr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("MANAGER")).andExpect(jsonPath("$.data.pendingApprovals").value(1))
                .andExpect(jsonPath("$.data.unreadNotifications").value(1))
                .andExpect(jsonPath("$.data.onLeaveToday", hasSize(1)))
                .andExpect(jsonPath("$.data.onLeaveToday[0].employeeId").value(emp.id()));
        send(get("/api/v1/dashboard"), hr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("HR"))
                .andExpect(jsonPath("$.data.pendingApprovals", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.onLeaveToday[*].employeeId", hasItem((int) emp.id().longValue())));
    }

    @Test
    void reportsSummaryOnLeaveAndCsvExport() throws Exception {
        AuthUser mgr = register("MANAGER", null);
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser formula = new AuthUser(newEmployeeId(), "=EVIL1", "=HYPERLINK(\"x\"),evil", "evil@company.com",
                "EMPLOYEE", "Engineering");
        when(directory.get(formula.id())).thenReturn(info(formula, null));
        LocalDate d = nextMonday(14);
        int year = d.getYear();
        long a = applyViaApi(emp, "CL", d, d.plusDays(1));
        applyViaApi(emp, "SL", d.plusDays(7), d.plusDays(7));
        applyViaApi(formula, "CL", d, d);
        postJson(LEAVE_REQUESTS + "/" + a + "/manager-action", mgr, "{\"action\":\"REJECT\",\"comment\":\"no\"}")
                .andExpect(status().isOk());

        for (String groupBy : new String[] {"employee", "department", "leaveType", "month"}) {
            send(get("/api/v1/reports/summary?groupBy=" + groupBy + "&year=" + year), hr).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
        }
        send(get("/api/v1/reports/summary?groupBy=employee&year=" + year), hr)
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].requests").value(2))
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].days").value(3.0))
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].rejected").value(1))
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].pending").value(1))
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].approved").value(0))
                .andExpect(jsonPath("$.data[?(@.key=='" + emp.id() + "')].label").value(emp.name()));
        send(get("/api/v1/reports/summary?groupBy=leaveType&year=" + year), hr)
                .andExpect(jsonPath("$.data[?(@.label=='Casual Leave')]").isNotEmpty());
        send(get("/api/v1/reports/summary?groupBy=month&year=" + year), hr)
                .andExpect(jsonPath("$.data[*].key", hasItem(String.format("%04d-%02d", year, d.getMonthValue()))));
        send(get("/api/v1/reports/summary?groupBy=nonsense"), hr).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        send(get("/api/v1/reports/summary?groupBy=month&from=2030-02-01&to=2030-01-01"), hr)
                .andExpect(status().isBadRequest());

        insertRequest(emp, mgr.id(), "CL", today(), today(), RequestStatus.APPROVED);
        send(get("/api/v1/reports/on-leave"), hr).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].employeeId", hasItem((int) emp.id().longValue())));
        send(get("/api/v1/reports/on-leave?from=" + today().plusDays(40) + "&to=" + today().plusDays(41)), hr)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].employeeId", not(hasItem((int) emp.id().longValue()))));

        MvcResult csv = send(get("/api/v1/reports/export?year=" + year), hr).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("charset=UTF-8")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("leave-report-" + year + ".csv")))
                .andReturn();
        String body = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.startsWith(
                "id,employeeCode,employeeName,department,leaveType,fromDate,toDate,workingDays,status,appliedOn,"
                        + "finalDecisionDate\r\n"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(emp.employeeCode() + ",User " + emp.id()
                + ",Engineering,Casual Leave," + d + "," + d.plusDays(1) + ",2.0,REJECTED_BY_MANAGER,"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"'=HYPERLINK(\"\"x\"\"),evil\""), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(",'=EVIL1,"), body);
        send(get("/api/v1/reports/export?year=" + year + "&status=BOGUS"), hr).andExpect(status().isBadRequest());
    }
}
