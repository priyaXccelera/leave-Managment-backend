package com.company.leave.identity.repo;

import com.company.leave.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Atomically revokes a still-active token; returns 0 when it was already revoked (reuse / race). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.employeeId = :employeeId and t.revoked = false")
    int revokeAllForEmployee(@Param("employeeId") Long employeeId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.employeeId = :employeeId and t.tokenHash = :hash and t.revoked = false")
    int revokeByHashForEmployee(@Param("employeeId") Long employeeId, @Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.employeeId = :employeeId and (t.revoked = true or t.expiresAt < :now)")
    int deleteStaleForEmployee(@Param("employeeId") Long employeeId, @Param("now") Instant now);
}
