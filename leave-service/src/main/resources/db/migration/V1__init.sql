CREATE TABLE leave_types (
    id                              BIGSERIAL PRIMARY KEY,
    name                            VARCHAR(100)  NOT NULL,
    code                            VARCHAR(20)   NOT NULL,
    yearly_quota                    NUMERIC(6,1)  NOT NULL DEFAULT 0,
    unlimited                       BOOLEAN       NOT NULL DEFAULT FALSE,
    paid                            BOOLEAN       NOT NULL DEFAULT TRUE,
    carry_forward_allowed           BOOLEAN       NOT NULL DEFAULT FALSE,
    max_carry_forward_days          NUMERIC(6,1)  NOT NULL DEFAULT 0,
    half_day_allowed                BOOLEAN       NOT NULL DEFAULT TRUE,
    attachment_required             BOOLEAN       NOT NULL DEFAULT FALSE,
    attachment_required_after_days  INTEGER,
    min_notice_days                 INTEGER       NOT NULL DEFAULT 0,
    active                          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_leave_types_code UNIQUE (code)
);
CREATE UNIQUE INDEX uq_leave_types_code_lower ON leave_types (LOWER(code));

CREATE TABLE leave_balances (
    id               BIGSERIAL PRIMARY KEY,
    employee_id      BIGINT       NOT NULL,
    leave_type_id    BIGINT       NOT NULL REFERENCES leave_types (id),
    year             INTEGER      NOT NULL,
    total            NUMERIC(6,1) NOT NULL DEFAULT 0,
    used             NUMERIC(6,1) NOT NULL DEFAULT 0,
    pending          NUMERIC(6,1) NOT NULL DEFAULT 0,
    carried_forward  NUMERIC(6,1) NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_leave_balances_emp_type_year UNIQUE (employee_id, leave_type_id, year)
);
CREATE INDEX idx_leave_balances_employee_year ON leave_balances (employee_id, year);
CREATE INDEX idx_leave_balances_year ON leave_balances (year);

CREATE TABLE balance_adjustments (
    id             BIGSERIAL PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    leave_type_id  BIGINT       NOT NULL REFERENCES leave_types (id),
    year           INTEGER      NOT NULL,
    days           NUMERIC(6,1) NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    adjusted_by    BIGINT       NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_balance_adjustments_employee ON balance_adjustments (employee_id, year);

CREATE TABLE holidays (
    id            BIGSERIAL PRIMARY KEY,
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(150) NOT NULL,
    type          VARCHAR(20)  NOT NULL,
    year          INTEGER      NOT NULL,
    CONSTRAINT uq_holidays_date UNIQUE (holiday_date)
);
CREATE INDEX idx_holidays_year ON holidays (year);

CREATE TABLE leave_requests (
    id                    BIGSERIAL PRIMARY KEY,
    employee_id           BIGINT       NOT NULL,
    employee_code         VARCHAR(50),
    employee_name         VARCHAR(150),
    department            VARCHAR(100),
    manager_id_snapshot   BIGINT,
    leave_type_id         BIGINT       NOT NULL REFERENCES leave_types (id),
    from_date             DATE         NOT NULL,
    to_date               DATE         NOT NULL,
    half_day              BOOLEAN      NOT NULL DEFAULT FALSE,
    half_day_session      VARCHAR(20),
    working_days          NUMERIC(6,1) NOT NULL,
    reason                VARCHAR(1000) NOT NULL,
    attachment_path       VARCHAR(500),
    contact_number        VARCHAR(30),
    status                VARCHAR(30)  NOT NULL,
    current_stage         VARCHAR(20)  NOT NULL,
    applied_on            TIMESTAMP WITH TIME ZONE NOT NULL,
    final_decision_date   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_leave_requests_employee ON leave_requests (employee_id);
CREATE INDEX idx_leave_requests_status ON leave_requests (status);
CREATE INDEX idx_leave_requests_from_date ON leave_requests (from_date);
CREATE INDEX idx_leave_requests_to_date ON leave_requests (to_date);
CREATE INDEX idx_leave_requests_department ON leave_requests (department);
CREATE INDEX idx_leave_requests_stage ON leave_requests (current_stage);
CREATE INDEX idx_leave_requests_manager ON leave_requests (manager_id_snapshot);
CREATE INDEX idx_leave_requests_type ON leave_requests (leave_type_id);

CREATE TABLE approval_history (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT       NOT NULL REFERENCES leave_requests (id) ON DELETE CASCADE,
    action      VARCHAR(30)  NOT NULL,
    actor_id    BIGINT,
    actor_name  VARCHAR(150),
    actor_role  VARCHAR(30),
    comment     VARCHAR(1000),
    acted_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_approval_history_request ON approval_history (request_id, acted_at);

CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    recipient_id  BIGINT        NOT NULL,
    message       VARCHAR(500)  NOT NULL,
    request_id    BIGINT,
    is_read       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, is_read, created_at);
