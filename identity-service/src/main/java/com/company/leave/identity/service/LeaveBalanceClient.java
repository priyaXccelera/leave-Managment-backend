package com.company.leave.identity.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.identity.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * Calls the leave-service internal API to create leave balances for an employee.
 */
@Component
public class LeaveBalanceClient {

    private static final Logger LOG = LoggerFactory.getLogger(LeaveBalanceClient.class);

    private final RestClient restClient;

    public LeaveBalanceClient(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(props.leaveServiceUrl())
                .defaultHeader("X-Internal-Key", props.internalApiKey())
                .requestFactory(factory)
                .build();
    }

    /**
     * Idempotently initialises the current-year balances for the employee.
     *
     * @throws ApiException 502 UPSTREAM_ERROR if leave-service is unreachable or answers with an error
     */
    public void initBalances(Long employeeId, LocalDate dateOfJoining) {
        try {
            restClient.post()
                    .uri("/internal/balances/init")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("employeeId", employeeId, "dateOfJoining", dateOfJoining.toString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            LOG.warn("leave-service balance init failed for employee {}: {}", employeeId, ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    "Leave service is unavailable; the employee could not be provisioned");
        }
    }
}
