package com.company.leave.identity.config;

import com.company.leave.common.security.JsonSecurityHandlers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sliding-window limiter for {@code POST /api/v1/auth/login}. The key is the client IP plus the
 * lower-cased identifier from the JSON body. This filter is intentionally not a Spring bean: it is added to the
 * security filter chain only (a bean would be registered a second time by the servlet container).
 */
public class LoginRateLimiter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final int limit;
    private final long windowMillis;
    private final ObjectMapper objectMapper;
    private final JsonSecurityHandlers errorWriter;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(System.currentTimeMillis());

    public LoginRateLimiter(int limit, int windowSeconds, ObjectMapper objectMapper, JsonSecurityHandlers errorWriter) {
        this.limit = limit;
        this.windowMillis = TimeUnit.SECONDS.toMillis(windowSeconds);
        this.objectMapper = objectMapper;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            errorWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body is too large");
            return;
        }
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        String identifier = extractIdentifier(body);
        if (identifier == null) {
            // Unparsable body: let the controller answer 400; nothing to key on.
            chain.doFilter(wrapped, response);
            return;
        }
        String key = clientIp(request) + "|" + identifier.trim().toLowerCase(Locale.ROOT);
        long retryAfter = tryAcquire(key);
        if (retryAfter > 0) {
            LOG.warn("Login rate limit exceeded for {}", clientIp(request));
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many login attempts. Try again later.");
            return;
        }
        chain.doFilter(wrapped, response);
    }

    /** Records an attempt; returns 0 if allowed, otherwise the number of seconds until a slot frees up. */
    long tryAcquire(String key) {
        long now = System.currentTimeMillis();
        cleanupIfDue(now);
        Deque<Long> deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now);
            if (deque.size() >= limit) {
                long wait = deque.peekFirst() + windowMillis - now;
                return Math.max(1, TimeUnit.MILLISECONDS.toSeconds(wait + 999));
            }
            deque.addLast(now);
            return 0;
        }
    }

    private void prune(Deque<Long> deque, long now) {
        while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
            deque.pollFirst();
        }
    }

    private void cleanupIfDue(long now) {
        long last = lastCleanup.get();
        if (now - last < windowMillis || !lastCleanup.compareAndSet(last, now)) {
            return;
        }
        attempts.forEach((key, deque) -> {
            synchronized (deque) {
                prune(deque, now);
                if (deque.isEmpty()) {
                    attempts.remove(key, deque);
                }
            }
        });
    }

    private String extractIdentifier(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode id = node.get("identifier");
            return id != null && id.isTextual() ? id.asText() : "";
        } catch (IOException ex) {
            LOG.debug("Login body is not valid JSON; leaving it to the controller");
            return null;
        }
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    /** Replays an already-consumed request body so downstream components can read it again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
