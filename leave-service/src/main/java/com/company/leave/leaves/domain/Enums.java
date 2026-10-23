package com.company.leave.leaves.domain;

/**
 * Namespace for the domain enumerations (persisted by name).
 */
public final class Enums {

    private Enums() {
    }

    public enum RequestStatus {
        PENDING_MANAGER, PENDING_HR, APPROVED, REJECTED_BY_MANAGER, REJECTED_BY_HR, CANCELLED
    }

    public enum Stage {
        MANAGER, HR, NONE
    }

    public enum HistoryAction {
        APPLIED, MANAGER_APPROVED, MANAGER_REJECTED, HR_APPROVED, HR_REJECTED, CANCELLED, WITHDRAWN, ESCALATED
    }

    public enum HalfDaySession {
        FIRST_HALF, SECOND_HALF
    }

    public enum HolidayType {
        PUBLIC, OPTIONAL
    }
}
