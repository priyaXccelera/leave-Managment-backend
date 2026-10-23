package com.company.leave.leaves.service;

import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.client.EmployeeDirectoryClient.EmployeeInfo;
import com.company.leave.leaves.domain.ApprovalHistory;
import com.company.leave.leaves.domain.Enums.HistoryAction;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.repo.ApprovalHistoryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Helpers shared by the request and approval services: history entries and recipient lookup.
 */
@Component
class WorkflowSupport {

    private final ApprovalHistoryRepository history;
    private final EmployeeDirectoryClient directory;

    WorkflowSupport(ApprovalHistoryRepository history, EmployeeDirectoryClient directory) {
        this.history = history;
        this.directory = directory;
    }

    ApprovalHistory record(LeaveRequest request, HistoryAction action, Long actorId, String actorName,
                           String actorRole, String comment) {
        ApprovalHistory h = new ApprovalHistory();
        h.setRequestId(request.getId());
        h.setAction(action);
        h.setActorId(actorId);
        h.setActorName(actorName);
        h.setActorRole(actorRole);
        h.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        h.setTimestamp(Instant.now());
        return history.save(h);
    }

    /** Ids of the active HR users, without {@code excludeId} (may be null). */
    List<Long> hrIds(Long excludeId) {
        return directory.hrUsers().stream()
                .filter(EmployeeInfo::active)
                .map(EmployeeInfo::id)
                .filter(id -> !Objects.equals(id, excludeId))
                .toList();
    }
}
