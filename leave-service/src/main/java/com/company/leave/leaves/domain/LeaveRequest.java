package com.company.leave.leaves.domain;

import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employee_code")
    private String employeeCode;

    @Column(name = "employee_name")
    private String employeeName;

    /** Department of the employee at the time of applying. */
    private String department;

    /** Manager of the employee at the time of applying. */
    @Column(name = "manager_id_snapshot")
    private Long managerIdSnapshot;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "half_day", nullable = false)
    private boolean halfDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "half_day_session")
    private HalfDaySession halfDaySession;

    @Column(name = "working_days", nullable = false)
    private BigDecimal workingDays = BigDecimal.ZERO;

    @Column(nullable = false)
    private String reason;

    @Column(name = "attachment_path")
    private String attachmentPath;

    @Column(name = "contact_number")
    private String contactNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false)
    private Stage currentStage;

    @Column(name = "applied_on", nullable = false)
    private Instant appliedOn;

    @Column(name = "final_decision_date")
    private Instant finalDecisionDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Long getManagerIdSnapshot() {
        return managerIdSnapshot;
    }

    public void setManagerIdSnapshot(Long managerIdSnapshot) {
        this.managerIdSnapshot = managerIdSnapshot;
    }

    public Long getLeaveTypeId() {
        return leaveTypeId;
    }

    public void setLeaveTypeId(Long leaveTypeId) {
        this.leaveTypeId = leaveTypeId;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    public boolean isHalfDay() {
        return halfDay;
    }

    public void setHalfDay(boolean halfDay) {
        this.halfDay = halfDay;
    }

    public HalfDaySession getHalfDaySession() {
        return halfDaySession;
    }

    public void setHalfDaySession(HalfDaySession halfDaySession) {
        this.halfDaySession = halfDaySession;
    }

    public BigDecimal getWorkingDays() {
        return workingDays;
    }

    public void setWorkingDays(BigDecimal workingDays) {
        this.workingDays = workingDays;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAttachmentPath() {
        return attachmentPath;
    }

    public void setAttachmentPath(String attachmentPath) {
        this.attachmentPath = attachmentPath;
    }

    public String getContactNumber() {
        return contactNumber;
    }

    public void setContactNumber(String contactNumber) {
        this.contactNumber = contactNumber;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Stage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(Stage currentStage) {
        this.currentStage = currentStage;
    }

    public Instant getAppliedOn() {
        return appliedOn;
    }

    public void setAppliedOn(Instant appliedOn) {
        this.appliedOn = appliedOn;
    }

    public Instant getFinalDecisionDate() {
        return finalDecisionDate;
    }

    public void setFinalDecisionDate(Instant finalDecisionDate) {
        this.finalDecisionDate = finalDecisionDate;
    }
}
