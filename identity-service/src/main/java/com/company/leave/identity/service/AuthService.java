package com.company.leave.identity.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.security.AuthUser;
import com.company.leave.common.security.JwtService;
import com.company.leave.identity.config.AppProperties;
import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.RefreshToken;
import com.company.leave.identity.dto.AuthDtos.TokenResponse;
import com.company.leave.identity.dto.AuthDtos.UserDto;
import com.company.leave.identity.repo.EmployeeRepository;
import com.company.leave.identity.repo.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final EmployeeRepository employees;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmployeeService employeeService;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();
    /** Verified against when the identifier is unknown so response timing does not reveal valid accounts. */
    private final String dummyHash;

    public AuthService(EmployeeRepository employees, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService, EmployeeService employeeService,
                       AppProperties props) {
        this.employees = employees;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.employeeService = employeeService;
        this.props = props;
        this.dummyHash = passwordEncoder.encode(randomToken());
    }

    @Transactional
    public TokenResponse login(String identifier, String password) {
        String key = identifier.trim().toLowerCase(Locale.ROOT);
        List<Employee> matches = employees.findByIdentifier(key);
        Employee employee = matches.isEmpty() ? null : matches.get(0);
        String hash = employee == null ? dummyHash : employee.getPasswordHash();
        boolean passwordOk = matchesSafely(password, hash) && employee != null;
        if (!passwordOk) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
        }
        if (!employee.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account is inactive");
        }
        refreshTokens.deleteStaleForEmployee(employee.getId(), Instant.now());
        return issue(employee);
    }

    @Transactional
    public TokenResponse refresh(String presentedToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(hash(presentedToken))
                .filter(t -> !t.isRevoked() && t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(AuthService::invalidRefresh);
        Employee employee = employees.findById(stored.getEmployeeId()).orElseThrow(AuthService::invalidRefresh);
        if (!employee.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account is inactive");
        }
        if (refreshTokens.revokeIfActive(stored.getId()) == 0) {
            throw invalidRefresh();
        }
        return issue(employee);
    }

    @Transactional
    public void logout(Long employeeId, String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            refreshTokens.revokeAllForEmployee(employeeId);
        } else {
            refreshTokens.revokeByHashForEmployee(employeeId, hash(presentedToken));
        }
    }

    @Transactional
    public void changePassword(Long employeeId, String currentPassword, String newPassword) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (!matchesSafely(currentPassword, employee.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw EmployeeService.fieldError("newPassword", "newPassword must be at most 72 bytes");
        }
        if (matchesSafely(newPassword, employee.getPasswordHash())) {
            throw EmployeeService.fieldError("newPassword", "New password must differ from the current password");
        }
        employee.setPasswordHash(passwordEncoder.encode(newPassword));
        employees.save(employee);
        refreshTokens.revokeAllForEmployee(employeeId);
        LOG.info("Password changed for employee {}", employeeId);
    }

    @Transactional(readOnly = true)
    public UserDto me(Long employeeId) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        return employeeService.toUserDto(employee);
    }

    // ------------------------------------------------------------------ helpers

    private TokenResponse issue(Employee e) {
        AuthUser principal = new AuthUser(e.getId(), e.getEmployeeCode(), e.getName(), e.getEmail(),
                e.getRole().name(), e.getDepartment());
        String access = jwtService.generateAccessToken(principal);
        String refreshValue = randomToken();
        Instant expiry = Instant.now().plus(props.refreshTokenTtlDays(), ChronoUnit.DAYS);
        refreshTokens.save(new RefreshToken(e.getId(), hash(refreshValue), expiry));
        return new TokenResponse(access, access, refreshValue, jwtService.getAccessTtlSeconds(),
                employeeService.toUserDto(e));
    }

    private boolean matchesSafely(String raw, String hash) {
        // BCrypt only considers 72 bytes; anything longer can never have been set as a password.
        if (raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            passwordEncoder.matches("x", hash);
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Refresh token is invalid, expired or revoked");
    }
}
