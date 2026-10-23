package com.company.leave.leaves.config;

import com.company.leave.common.security.JsonSecurityHandlers;
import com.company.leave.common.security.JwtAuthenticationFilter;
import com.company.leave.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /** Leave-service only validates tokens; the TTL is irrelevant here. */
    private static final long UNUSED_TTL_SECONDS = 3600;

    @Bean
    public JwtService jwtService(LeaveProperties properties) {
        return new JwtService(properties.jwtSecret(), UNUSED_TTL_SECONDS);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   LeaveProperties properties, JsonSecurityHandlers handlers)
            throws Exception {
        // Filters are created here (not beans) so the servlet container does not register them twice.
        InternalKeyFilter internalKeyFilter = new InternalKeyFilter(properties.internalApiKey(), handlers);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);

        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/health").permitAll()
                        // protected by InternalKeyFilter, which answers 401 itself
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(internalKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
