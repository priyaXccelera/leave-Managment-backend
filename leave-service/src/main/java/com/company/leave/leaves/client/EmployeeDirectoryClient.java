package com.company.leave.leaves.client;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse;
import com.company.leave.leaves.config.InternalKeyFilter;
import com.company.leave.leaves.config.LeaveProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Read-only access to the employee directory owned by identity-service ({@code /internal/employees}).
 */
@Component
public class EmployeeDirectoryClient {

    private static final Logger LOG = LoggerFactory.getLogger(EmployeeDirectoryClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmployeeInfo(Long id, String employeeCode, String name, String email, String role,
                               String department, Long managerId, boolean active) {
    }

    private final RestClient restClient;

    public EmployeeDirectoryClient(LeaveProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(properties.identityUrl())
                .requestFactory(factory)
                .defaultHeader(InternalKeyFilter.HEADER, properties.internalApiKey())
                .build();
    }

    /** @throws ApiException 404 when the employee is unknown, 503 when identity-service is unreachable */
    public EmployeeInfo get(Long id) {
        EmployeeInfo info = call(() -> restClient.get().uri("/internal/employees/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<EmployeeInfo>>() { }), true);
        if (info == null) {
            throw ApiException.notFound("Employee not found");
        }
        return info;
    }

    /** Active HR users. */
    public List<EmployeeInfo> hrUsers() {
        return list(() -> restClient.get().uri("/internal/employees/hr").retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<EmployeeInfo>>>() { }));
    }

    /** Active direct reports of the manager. */
    public List<EmployeeInfo> directReports(Long managerId) {
        return list(() -> restClient.get().uri("/internal/employees/by-manager/{id}", managerId).retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<EmployeeInfo>>>() { }));
    }

    public List<EmployeeInfo> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return list(() -> restClient.get().uri(b -> b.path("/internal/employees").queryParam("ids", csv).build())
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<List<EmployeeInfo>>>() { }));
    }

    public List<EmployeeInfo> byDepartment(String department) {
        return list(() -> restClient.get()
                .uri(b -> b.path("/internal/employees").queryParam("department", "{d}").build(department))
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<List<EmployeeInfo>>>() { }));
    }

    private List<EmployeeInfo> list(Supplier<ApiResponse<List<EmployeeInfo>>> request) {
        List<EmployeeInfo> data = call(request, false);
        return data == null ? List.of() : data;
    }

    private <T> T call(Supplier<ApiResponse<T>> request, boolean notFoundIsNull) {
        try {
            ApiResponse<T> response = request.get();
            return response == null ? null : response.data();
        } catch (HttpClientErrorException.NotFound ex) {
            if (notFoundIsNull) {
                return null;
            }
            throw ApiException.notFound("Employee not found");
        } catch (RestClientResponseException ex) {
            LOG.warn("Identity service answered {}", ex.getStatusCode());
            throw ApiException.unavailable("Employee directory is unavailable");
        } catch (RestClientException ex) {
            LOG.warn("Identity service call failed: {}", ex.getMessage());
            throw ApiException.unavailable("Employee directory is unavailable");
        }
    }
}
