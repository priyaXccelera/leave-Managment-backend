package com.company.leave.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class GatewayFailFastTest {

    @Test
    void startupFailsWhenServiceUrlsAreMissing() {
        // IDENTITY_SERVICE_URL / LEAVE_SERVICE_URL are intentionally not provided
        assertThatThrownBy(() -> new SpringApplicationBuilder(GatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0")
                .run())
                .hasMessageContaining("IDENTITY_SERVICE_URL")
                .hasMessageContaining("LEAVE_SERVICE_URL");
    }
}
