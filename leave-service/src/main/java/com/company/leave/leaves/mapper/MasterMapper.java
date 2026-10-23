package com.company.leave.leaves.mapper;

import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.domain.LeaveBalance;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.domain.Notification;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.dto.MasterDtos.HolidayDto;
import com.company.leave.leaves.dto.MasterDtos.LeaveTypeDto;
import com.company.leave.leaves.dto.MasterDtos.NotificationDto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Entity to DTO conversions for master data, balances and notifications.
 */
public final class MasterMapper {

    private MasterMapper() {
    }

    public static LeaveTypeDto toDto(LeaveType t) {
        return new LeaveTypeDto(t.getId(), t.getName(), t.getCode(), days(t.getYearlyQuota()), t.isUnlimited(),
                t.isPaid(), t.isCarryForwardAllowed(), days(t.getMaxCarryForwardDays()), t.isHalfDayAllowed(),
                t.isAttachmentRequired(), t.getAttachmentRequiredAfterDays(), t.getMinNoticeDays(), t.isActive());
    }

    public static HolidayDto toDto(Holiday h) {
        return new HolidayDto(h.getId(), h.getDate(), h.getName(), h.getType(), h.getYear());
    }

    public static BalanceDto toDto(LeaveBalance b, LeaveType t) {
        return new BalanceDto(t.getId(), t.getName(), t.getCode(), t.getCode(), b.getYear(), days(b.getTotal()),
                days(b.getUsed()), days(b.getPending()), days(b.available()), days(b.getCarriedForward()),
                t.isUnlimited());
    }

    public static NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getMessage(), n.getRequestId(), n.isRead(), n.getCreatedAt());
    }

    /** Normalises a day count to one decimal place. */
    public static BigDecimal days(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }
}
