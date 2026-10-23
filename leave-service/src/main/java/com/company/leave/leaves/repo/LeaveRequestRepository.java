package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.LeaveRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long>,
        JpaSpecificationExecutor<LeaveRequest> {

    /** Locks the request row (SELECT ... FOR UPDATE) so concurrent decisions are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LeaveRequest r where r.id = :id")
    Optional<LeaveRequest> lockById(@Param("id") Long id);

    /** Requests of one employee in the given statuses whose date range intersects [fromDate, toDate]. */
    @Query("select r from LeaveRequest r where r.employeeId = :employeeId and r.status in :statuses "
            + "and r.fromDate <= :toDate and r.toDate >= :fromDate order by r.fromDate")
    List<LeaveRequest> findOverlapping(@Param("employeeId") Long employeeId,
                                       @Param("statuses") Collection<RequestStatus> statuses,
                                       @Param("fromDate") LocalDate fromDate,
                                       @Param("toDate") LocalDate toDate);

    /** Requests in the statuses (all employees) whose date range intersects [fromDate, toDate]. */
    @Query("select r from LeaveRequest r where r.status in :statuses "
            + "and r.fromDate <= :toDate and r.toDate >= :fromDate order by r.fromDate, r.id")
    List<LeaveRequest> findInRange(@Param("statuses") Collection<RequestStatus> statuses,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate);

    /** Same as {@link #findInRange} restricted to the given employees. */
    @Query("select r from LeaveRequest r where r.status in :statuses and r.employeeId in :employeeIds "
            + "and r.fromDate <= :toDate and r.toDate >= :fromDate order by r.fromDate, r.id")
    List<LeaveRequest> findInRangeForEmployees(@Param("statuses") Collection<RequestStatus> statuses,
                                               @Param("employeeIds") Collection<Long> employeeIds,
                                               @Param("fromDate") LocalDate fromDate,
                                               @Param("toDate") LocalDate toDate);

    /** Same as {@link #findInRange} restricted to one department (snapshot taken at apply time). */
    @Query("select r from LeaveRequest r where r.status in :statuses and r.department = :department "
            + "and r.fromDate <= :toDate and r.toDate >= :fromDate order by r.fromDate, r.id")
    List<LeaveRequest> findInRangeForDepartment(@Param("statuses") Collection<RequestStatus> statuses,
                                                @Param("department") String department,
                                                @Param("fromDate") LocalDate fromDate,
                                                @Param("toDate") LocalDate toDate);

    /** Employees on approved leave on the given day. */
    default List<LeaveRequest> findOnLeave(LocalDate day) {
        return findInRange(List.of(RequestStatus.APPROVED), day, day);
    }

    /** Employees (restricted to the ids) on approved leave on the given day. */
    default List<LeaveRequest> findOnLeaveForEmployees(LocalDate day, Collection<Long> employeeIds) {
        return findInRangeForEmployees(List.of(RequestStatus.APPROVED), employeeIds, day, day);
    }

    /** Escalation candidates: requests in the status that were applied before the cut-off. */
    List<LeaveRequest> findByStatusAndAppliedOnBefore(RequestStatus status, Instant cutoff);

    long countByStatus(RequestStatus status);

    long countByStatusAndEmployeeIdIn(RequestStatus status, Collection<Long> employeeIds);

    long countByStatusAndManagerIdSnapshot(RequestStatus status, Long managerId);

    long countByEmployeeIdAndStatus(Long employeeId, RequestStatus status);
}
