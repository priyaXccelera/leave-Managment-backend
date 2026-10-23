package com.company.leave.leaves.service;

import com.company.leave.leaves.config.LeaveProperties;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Escalates requests that sat in PENDING_MANAGER for longer than {@code leave.escalation-days} to HR.
 * Each request is escalated in its own transaction (through the {@link ApprovalService} proxy), so one
 * failure never blocks the others.
 */
@Component
public class EscalationJob {

    private static final Logger LOG = LoggerFactory.getLogger(EscalationJob.class);
    private static final int BATCH_SIZE = 100;

    private final LeaveRequestRepository requests;
    private final ApprovalService approvals;
    private final LeaveProperties properties;

    public EscalationJob(LeaveRequestRepository requests, ApprovalService approvals, LeaveProperties properties) {
        this.requests = requests;
        this.approvals = approvals;
        this.properties = properties;
    }

    @Scheduled(cron = "${leave.escalation-cron}", zone = "UTC")
    public void run() {
        int escalated = runOnce(Instant.now());
        if (escalated > 0) {
            LOG.info("Escalation job escalated {} request(s) to HR", escalated);
        }
    }

    /**
     * @param now reference time; requests applied more than {@code escalation-days} before it are escalated
     * @return number of requests escalated
     */
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(properties.escalationDays()));
        int escalated = 0;
        long lastId = 0;
        while (true) {
            List<LeaveRequest> batch = requests.findByStatusAndAppliedOnBeforeAndIdGreaterThanOrderByIdAsc(
                    RequestStatus.PENDING_MANAGER, cutoff, lastId, PageRequest.ofSize(BATCH_SIZE));
            if (batch.isEmpty()) {
                return escalated;
            }
            for (LeaveRequest r : batch) {
                lastId = r.getId();
                try {
                    if (approvals.escalate(r.getId())) {
                        escalated++;
                    }
                } catch (RuntimeException ex) {
                    LOG.error("Could not escalate leave request {}", r.getId(), ex);
                }
            }
        }
    }
}
