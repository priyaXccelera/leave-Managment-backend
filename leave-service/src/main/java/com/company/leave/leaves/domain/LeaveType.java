package com.company.leave.leaves.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "leave_types")
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column(name = "yearly_quota", nullable = false)
    private BigDecimal yearlyQuota = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean unlimited;

    @Column(nullable = false)
    private boolean paid = true;

    @Column(name = "carry_forward_allowed", nullable = false)
    private boolean carryForwardAllowed;

    @Column(name = "max_carry_forward_days", nullable = false)
    private BigDecimal maxCarryForwardDays = BigDecimal.ZERO;

    @Column(name = "half_day_allowed", nullable = false)
    private boolean halfDayAllowed = true;

    @Column(name = "attachment_required", nullable = false)
    private boolean attachmentRequired;

    @Column(name = "attachment_required_after_days")
    private Integer attachmentRequiredAfterDays;

    @Column(name = "min_notice_days", nullable = false)
    private int minNoticeDays;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BigDecimal getYearlyQuota() {
        return yearlyQuota;
    }

    public void setYearlyQuota(BigDecimal yearlyQuota) {
        this.yearlyQuota = yearlyQuota;
    }

    public boolean isUnlimited() {
        return unlimited;
    }

    public void setUnlimited(boolean unlimited) {
        this.unlimited = unlimited;
    }

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    public boolean isCarryForwardAllowed() {
        return carryForwardAllowed;
    }

    public void setCarryForwardAllowed(boolean carryForwardAllowed) {
        this.carryForwardAllowed = carryForwardAllowed;
    }

    public BigDecimal getMaxCarryForwardDays() {
        return maxCarryForwardDays;
    }

    public void setMaxCarryForwardDays(BigDecimal maxCarryForwardDays) {
        this.maxCarryForwardDays = maxCarryForwardDays;
    }

    public boolean isHalfDayAllowed() {
        return halfDayAllowed;
    }

    public void setHalfDayAllowed(boolean halfDayAllowed) {
        this.halfDayAllowed = halfDayAllowed;
    }

    public boolean isAttachmentRequired() {
        return attachmentRequired;
    }

    public void setAttachmentRequired(boolean attachmentRequired) {
        this.attachmentRequired = attachmentRequired;
    }

    public Integer getAttachmentRequiredAfterDays() {
        return attachmentRequiredAfterDays;
    }

    public void setAttachmentRequiredAfterDays(Integer attachmentRequiredAfterDays) {
        this.attachmentRequiredAfterDays = attachmentRequiredAfterDays;
    }

    public int getMinNoticeDays() {
        return minNoticeDays;
    }

    public void setMinNoticeDays(int minNoticeDays) {
        this.minNoticeDays = minNoticeDays;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
