package com.company.leave.identity.config;

import com.company.leave.common.security.JsonSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Guards {@code /internal/**} with the shared {@code X-Internal-Key}. A valid key authenticates the request with the
 * dedicated {@code INTERNAL} authority. Not a Spring bean on purpose (registered in the security chain only).
 */
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";
    public static final String AUTHORITY = "INTERNAL";

    private final byte[] expectedKey;
    private final JsonSecurityHandlers errorWriter;

    public InternalKeyFilter(String expectedKey, JsonSecurityHandlers errorWriter) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals("/internal") || uri.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid internal key");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        chain.doFilter(request, response);
    }
}
