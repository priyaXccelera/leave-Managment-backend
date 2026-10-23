package com.company.leave.identity.config;

import com.company.leave.common.security.JsonSecurityHandlers;
import com.company.leave.common.security.JwtAuthenticationFilter;
import com.company.leave.common.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtService jwtService(AppProperties props) {
        return new JwtService(props.jwtSecret(), props.accessTokenTtlSeconds());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties props, JwtService jwtService,
                                                   JsonSecurityHandlers handlers, ObjectMapper objectMapper)
            throws Exception {
        // Filters are created here and are NOT beans, so the servlet container does not register them a second time.
        LoginRateLimiter rateLimiter = new LoginRateLimiter(props.loginRateLimit(), props.loginRateWindowSeconds(),
                objectMapper, handlers);
        InternalKeyFilter internalKeyFilter = new InternalKeyFilter(props.internalApiKey(), handlers);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .authorizeHttpRequests(a -> a
                        // all HTTP methods are permitted here so that wrong methods reach MVC and answer 405 + Allow
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/actuator/health", "/actuator/health/**", "/health").permitAll()
                        .requestMatchers("/internal/**").hasAuthority(InternalKeyFilter.AUTHORITY)
                        // defence in depth: controllers also call CurrentUser.requireRole("HR")
                        .requestMatchers("/api/v1/employees", "/api/v1/employees/**").hasRole("HR")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimiter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
