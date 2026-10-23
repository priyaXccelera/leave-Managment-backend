package com.company.leave.leaves.service;

import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.client.EmployeeDirectoryClient.EmployeeInfo;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.dto.MasterDtos.DashboardDto;
import com.company.leave.leaves.dto.MasterDtos.HolidayDto;
import com.company.leave.leaves.dto.MasterDtos.OnLeaveDto;
import com.company.leave.leaves.mapper.LeaveRequestMapper;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.HolidayRepository;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Role specific landing-page summary.
 */
@Service
public class DashboardService {

    private static final int UPCOMING_LIMIT = 5;
    private static final Set<RequestStatus> UPCOMING_STATUSES = EnumSet.of(RequestStatus.PENDING_MANAGER,
            RequestStatus.PENDING_HR, RequestStatus.APPROVED);

    private final BalanceService balances;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository leaveTypes;
    private final HolidayRepository holidays;
    private final NotificationService notifications;
    private final LeaveRequestService requestService;
    private final EmployeeDirectoryClient directory;

    public DashboardService(BalanceService balances, LeaveRequestRepository requests,
                            LeaveTypeRepository leaveTypes, HolidayRepository holidays,
                            NotificationService notifications, LeaveRequestService requestService,
                            EmployeeDirectoryClient directory) {
        this.balances = balances;
        this.requests = requests;
        this.leaveTypes = leaveTypes;
        this.holidays = holidays;
        this.notifications = notifications;
        this.requestService = requestService;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    public DashboardDto build(AuthUser user) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<BalanceDto> balanceList = balances.forEmployee(user.id(), null);
        List<LeaveRequestDto> upcoming = requestService.toDtos(
                requests.findByEmployeeIdAndStatusInAndToDateGreaterThanEqualOrderByFromDateAscIdAsc(
                        user.id(), UPCOMING_STATUSES, today, PageRequest.ofSize(UPCOMING_LIMIT)));
        List<HolidayDto> upcomingHolidays = holidays
                .findByDateGreaterThanEqualOrderByDate(today, PageRequest.ofSize(UPCOMING_LIMIT)).stream()
                .map(MasterMapper::toDto).toList();

        long pending = 0;
        List<OnLeaveDto> onLeave = List.of();
        if (user.isHr()) {
            pending = requests.countByStatus(RequestStatus.PENDING_HR)
                    - requests.countByEmployeeIdAndStatus(user.id(), RequestStatus.PENDING_HR);
            onLeave = toOnLeave(requests.findOnLeave(today));
        } else if (user.isManager()) {
            pending = requests.countByStatusAndManagerIdSnapshot(RequestStatus.PENDING_MANAGER, user.id());
            List<Long> reportIds = directory.directReports(user.id()).stream().map(EmployeeInfo::id).toList();
            if (!reportIds.isEmpty()) {
                onLeave = toOnLeave(requests.findOnLeaveForEmployees(today, reportIds));
            }
        }
        return new DashboardDto(user.role(), pending, balanceList, upcoming, upcomingHolidays,
                notifications.unreadCount(user.id()), onLeave);
    }

    private List<OnLeaveDto> toOnLeave(List<LeaveRequest> found) {
        Map<Long, String> names = leaveTypes.findAll().stream()
                .collect(Collectors.toMap(LeaveType::getId, LeaveType::getName));
        return found.stream().map(r -> LeaveRequestMapper.toOnLeave(r, names.get(r.getLeaveTypeId()))).toList();
    }
}
