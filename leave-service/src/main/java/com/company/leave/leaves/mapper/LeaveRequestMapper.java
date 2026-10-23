package com.company.leave.leaves.mapper;

import com.company.leave.leaves.domain.ApprovalHistory;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.LeaveRequestDtos.EmployeeRef;
import com.company.leave.leaves.dto.LeaveRequestDtos.HistoryEntry;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveTypeRef;
import com.company.leave.leaves.dto.MasterDtos.CalendarEntry;
import com.company.leave.leaves.dto.MasterDtos.OnLeaveDto;

import java.util.Comparator;
import java.util.List;

/**
 * Entity to DTO conversions of leave requests.
 */
public final class LeaveRequestMapper {

    private static final Comparator<ApprovalHistory> HISTORY_ORDER = Comparator
            .comparing(ApprovalHistory::getTimestamp).thenComparing(ApprovalHistory::getId);

    private LeaveRequestMapper() {
    }

    public static String attachmentUrl(LeaveRequest r) {
        return r.getAttachmentPath() == null ? null : "/api/v1/leave-requests/" + r.getId() + "/attachment";
    }

    public static LeaveRequestDto toDto(LeaveRequest r, LeaveType type, List<ApprovalHistory> history) {
        List<HistoryEntry> entries = history.stream().sorted(HISTORY_ORDER)
                .map(h -> new HistoryEntry(h.getAction(), h.getActorName(), h.getActorRole(), h.getComment(),
                        h.getTimestamp()))
                .toList();
        LeaveTypeRef typeRef = type == null ? null : new LeaveTypeRef(type.getId(), type.getName(), type.getCode());
        return new LeaveRequestDto(r.getId(),
                new EmployeeRef(r.getEmployeeId(), r.getEmployeeCode(), r.getEmployeeName(), r.getDepartment()),
                typeRef, r.getFromDate(), r.getToDate(), r.isHalfDay(), r.getHalfDaySession(),
                MasterMapper.days(r.getWorkingDays()), r.getReason(), r.getContactNumber(), attachmentUrl(r),
                r.getStatus(), r.getCurrentStage(), r.getAppliedOn(), r.getFinalDecisionDate(), entries);
    }

    public static CalendarEntry toCalendar(LeaveRequest r, String leaveTypeName) {
        return new CalendarEntry(r.getId(), r.getEmployeeId(), r.getEmployeeName(), r.getDepartment(),
                leaveTypeName, r.getFromDate(), r.getToDate(), r.getStatus(),
                MasterMapper.days(r.getWorkingDays()));
    }

    public static OnLeaveDto toOnLeave(LeaveRequest r, String leaveTypeName) {
        return new OnLeaveDto(r.getId(), r.getEmployeeId(), r.getEmployeeCode(), r.getEmployeeName(),
                r.getDepartment(), leaveTypeName, r.getFromDate(), r.getToDate(), r.isHalfDay(),
                MasterMapper.days(r.getWorkingDays()));
    }
}
