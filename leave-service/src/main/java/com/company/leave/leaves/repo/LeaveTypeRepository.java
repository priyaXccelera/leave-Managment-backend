package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    List<LeaveType> findAllByOrderByIdAsc();

    List<LeaveType> findByActiveTrueOrderByIdAsc();

    Optional<LeaveType> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
