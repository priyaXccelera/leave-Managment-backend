package com.company.leave.gateway.config;

import java.util.List;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Fails fast, with a clear message, when a required downstream URL is not configured.
 * Registered through META-INF/spring.factories so it runs before any bean (including the
 * gateway routes) is created.
 */
public class RequiredEnvListener implements ApplicationListener<ApplicationPreparedEvent> {

    static final List<String> REQUIRED = List.of("IDENTITY_SERVICE_URL", "LEAVE_SERVICE_URL");

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        ConfigurableEnvironment env = event.getApplicationContext().getEnvironment();
        List<String> missing = REQUIRED.stream()
                .filter(name -> !StringUtils.hasText(env.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variable(s): " + String.join(", ", missing)
                            + ". The api-gateway cannot start without them.");
        }
    }
}
