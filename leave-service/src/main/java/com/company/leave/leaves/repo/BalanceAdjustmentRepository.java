package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.BalanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BalanceAdjustmentRepository extends JpaRepository<BalanceAdjustment, Long> {

    List<BalanceAdjustment> findByEmployeeIdAndYearOrderByCreatedAtAsc(Long employeeId, int year);
}
