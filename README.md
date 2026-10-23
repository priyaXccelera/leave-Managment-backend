# Employee Leave Management System (backend)

Java 21 / Spring Boot 3.5.0 / Maven 3.9.9 microservices on PostgreSQL 17.2 (JPA/Hibernate + Flyway).
The public REST contract lives under `/api/v1` (see [docs/openapi.yaml](docs/openapi.yaml)).

## Architecture

| Module | Port | Responsibility |
|---|---|---|
| `api-gateway` | 8080 (only published port) | Spring Cloud Gateway: path routing, env-configurable CORS, `GET /health`, JSON errors. `/internal/**` is **not** routed. |
| `identity-service` | 8081 | DB `identity_db`: employees, refresh tokens, auth (login / refresh / logout / change-password / me), employee CRUD, login rate limiting, internal directory API (`/internal/employees/**`). |
| `leave-service` | 8082 | DB `leave_db`: leave types, holidays, balances, adjustment log, leave requests, approval history, notifications, reports, dashboard, team calendar, scheduled jobs (escalation, year-end carry forward). |
| `common` | - | Response envelope, `ApiException`, global exception handler, HS256 JWT service/filter, `AuthUser`. |

* Both services validate the same HS256 JWT (`JWT_SECRET`) and enforce RBAC server-side (EMPLOYEE, MANAGER, HR).
* Service-to-service calls use `/internal/**` guarded by the `X-Internal-Key` header (`INTERNAL_API_KEY`):
  leave -> identity (employee by id, HR users, direct reports, by ids / department) and identity -> leave (`POST /internal/balances/init`).
* Responses: success `{success:true,data,meta{page,pageSize,total}}` (meta only for lists); error
  `{success:false,error{code,message,details[{field,message}]}}`. Dates `YYYY-MM-DD`, timestamps ISO-8601 UTC.
* Errors: malformed body 400 `MALFORMED_BODY`, validation 400 `VALIDATION_ERROR` with details, unknown route 404, wrong method 405 with `Allow`,
  missing/invalid credentials 401, forbidden 403, invalid workflow transition 409 `INVALID_TRANSITION`, login rate limit 429 `RATE_LIMITED`, 500 without stack trace.
* Lists paginate in the database: `page` (1-based), `pageSize` (default 20, max 100), `sort=field,asc|desc` plus filters.
* Schema is created by Flyway migrations; settings fail fast when a required env var is missing; graceful shutdown is enabled.

## Run with Docker Compose

```bash
cp .env.example .env        # optional: compose has development defaults for every variable
docker compose up -d --build
curl http://localhost:8080/health
```

Compose starts `postgres:17.2` (databases `identity_db` and `leave_db` created by `docker/postgres-init.sql`),
`identity-service`, `leave-service` and `api-gateway`. Only the gateway publishes a host port (`GATEWAY_PORT`, default 8080).
Change `JWT_SECRET`, `INTERNAL_API_KEY` and `POSTGRES_PASSWORD` for anything other than local use.

## Run locally (without Docker for the services)

Needs JDK 21, Maven 3.9.9 and a PostgreSQL with databases `identity_db` and `leave_db`.

```bash
mvn -B -DskipTests package
# export the variables listed in .env.example (IDENTITY_DB_*, LEAVE_DB_*, JWT_SECRET, INTERNAL_API_KEY, service URLs, ...)
java -jar identity-service/target/identity-service.jar &
java -jar leave-service/target/leave-service.jar &
java -jar api-gateway/target/api-gateway.jar
```

## Tests

Tests run against a **real PostgreSQL** (no H2). Each service uses its own schema (`identity_test`, `leave_test`) which is cleaned and re-migrated per run.
Point them at any PostgreSQL, e.g.:

```bash
docker run -d --name leave-test-db -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=app postgres:17.2
export TEST_DB_URL=jdbc:postgresql://localhost:5432/app TEST_DB_USER=postgres TEST_DB_PASSWORD=postgres
mvn -B verify
```

Coverage: working-day calculation, balance reservation/release/deduct/restore/adjust/pro-rating/year-end carry forward, the full approval
workflow (approve, manager reject, HR reject, cancellation, escalation, self-approval rejection), permission checks via MockMvc, identity auth
(login by code/email, refresh rotation, logout, inactive users, rate limit), and gateway routing/error handling.

## Seed data

All seeded users share the password **`Password@123`** (override with `SEED_PASSWORD`).

| Code | Email | Role | Reports to |
|---|---|---|---|
| HR001 | hr@company.com | HR | - |
| MGR001 | manager1@company.com | MANAGER | - |
| MGR002 | manager2@company.com | MANAGER | - |
| EMP001 | emp1@company.com | EMPLOYEE | manager1 |
| EMP002 | emp2@company.com | EMPLOYEE | manager1 |
| EMP003 | emp3@company.com | EMPLOYEE | manager1 |
| EMP004 | emp4@company.com | EMPLOYEE | manager2 |
| EMP005 | emp5@company.com | EMPLOYEE | manager2 |

Leave types (Flyway): Casual 12/yr, Sick 10/yr (certificate required when days > 2, backdating allowed), Earned/Privilege 15/yr (carry forward max 30),
Maternity 182 days, Paternity 10, Compensatory Off, Loss of Pay (unlimited, unpaid). Current-year sample holidays are seeded at startup.
Balances for the current year are created for every seeded employee (and for every employee HR creates, pro-rated by joining month in the first year).

## Workflow summary

1. Submit -> `PENDING_MANAGER` (manager notified); a manager's own leave, an HR user's own leave, or an employee without a manager starts at `PENDING_HR`. Days are reserved in `pending`.
2. Manager approve -> `PENDING_HR` (all HR notified); manager reject (comment mandatory) -> `REJECTED_BY_MANAGER`, days released.
3. HR approve -> `APPROVED` (pending -> used; employee and manager notified); HR reject (comment mandatory) -> `REJECTED_BY_HR`, days released. HR cannot act on their own request.
4. Employee cancels while pending at any stage (released) or an approved leave before its start date (balance restored). Other transitions return 409.
5. Scheduled escalation moves `PENDING_MANAGER` requests older than `ESCALATION_DAYS` to `PENDING_HR` with an `ESCALATED` history entry; a year-end job carries balances forward (capped by `maxCarryForwardDays`) and creates next-year balances.

Business rules enforced on submit: from <= to, no overlap with pending/approved leave, sufficient balance (not for unpaid types), mandatory attachment
(sick leave > 2 days; send multipart with an `attachment` part), at least one working day (weekends from `WEEKEND_DAYS` and PUBLIC holidays excluded), start not earlier than
today minus `BACKDATE_DAYS` (sick leave exempt), minimum notice per type, half-day only for single-day ranges where the type allows it. `POST /api/v1/leave-requests/preview` reports the same checks as warnings.

## Configuration

Every variable is listed in `.env.example`. Required variables have no defaults; a service exits non-zero with a clear message when one is missing.
