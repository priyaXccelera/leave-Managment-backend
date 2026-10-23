package com.company.leave.leaves.config;

import com.company.leave.common.security.JsonSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /internal/**} with the shared {@code X-Internal-Key} header. Not a Spring bean on purpose:
 * it is instantiated by {@link SecurityConfig} so Boot does not register it a second time as a servlet filter.
 */
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    private final byte[] expectedKey;
    private final JsonSecurityHandlers errorWriter;

    public InternalKeyFilter(String expectedKey, JsonSecurityHandlers errorWriter) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/internal") || path.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid internal key");
            return;
        }
        chain.doFilter(request, response);
    }
}
