package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.ApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    List<ApprovalHistory> findByRequestIdOrderByTimestampAsc(Long requestId);

    List<ApprovalHistory> findByRequestIdInOrderByTimestampAsc(Collection<Long> requestIds);
}
