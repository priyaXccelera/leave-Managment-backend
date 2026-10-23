package com.company.leave.gateway;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Public liveness endpoint. Always 200 while the gateway itself is up.
 * Other methods yield 405 (+ Allow) through {@link GatewayErrorHandler}.
 */
@RestController
public class HealthController {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of("success", true, "data", Map.of("status", "UP")));
    }
}
