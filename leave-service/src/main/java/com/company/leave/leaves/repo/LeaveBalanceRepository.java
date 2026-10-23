package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.LeaveBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(Long employeeId, Long leaveTypeId, int year);

    /** Row-locking variant (SELECT ... FOR UPDATE) used by every mutating balance operation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LeaveBalance b where b.employeeId = :employeeId and b.leaveTypeId = :leaveTypeId "
            + "and b.year = :year")
    Optional<LeaveBalance> lockByEmployeeIdAndLeaveTypeIdAndYear(@Param("employeeId") Long employeeId,
                                                                 @Param("leaveTypeId") Long leaveTypeId,
                                                                 @Param("year") int year);

    List<LeaveBalance> findByEmployeeIdAndYearOrderByLeaveTypeIdAsc(Long employeeId, int year);

    List<LeaveBalance> findByYear(int year);

    boolean existsByEmployeeIdAndLeaveTypeIdAndYear(Long employeeId, Long leaveTypeId, int year);
}
