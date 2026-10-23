CREATE TABLE employees (
    id              BIGSERIAL PRIMARY KEY,
    employee_code   VARCHAR(30)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    email           VARCHAR(190) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    department      VARCHAR(100) NOT NULL,
    designation     VARCHAR(100),
    date_of_joining DATE         NOT NULL,
    manager_id      BIGINT       REFERENCES employees (id),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_employees_role CHECK (role IN ('EMPLOYEE', 'MANAGER', 'HR')),
    CONSTRAINT chk_employees_not_own_manager CHECK (manager_id IS NULL OR manager_id <> id)
);

CREATE UNIQUE INDEX uq_employees_employee_code ON employees (lower(employee_code));
CREATE UNIQUE INDEX uq_employees_email ON employees (lower(email));
CREATE INDEX idx_employees_manager_id ON employees (manager_id);
CREATE INDEX idx_employees_department ON employees (department);
CREATE INDEX idx_employees_role ON employees (role);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    employee_id BIGINT      NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_employee_id ON refresh_tokens (employee_id);
