# checkpoint — Employee Leave Management (Java 21 / Spring Boot 3.5.0 / Maven / PostgreSQL 17.2)

Project root: /outputs/job-0205d132-c36e-4c5e-9739-d31b55b82571/  (Maven multi-module; no wrapper; build via docker maven:3.9.9-eclipse-temurin-21)

## Stack (immutable)
Java 21, Spring Boot 3.5.0, Spring Cloud 2025.0.0 (gateway only), Maven, PostgreSQL via JPA/Hibernate + Flyway, jjwt 0.12.6.
No Eureka, no Kafka, no config-server. Modules: common, identity-service(8081), leave-service(8082), api-gateway(8000 — changed from 8080 per deploy contract; only published port).
Parent pom + common module + .gitignore are DONE (written by orchestrator). Do not edit them.
Base package: com.company.leave.{common | identity | leaves | gateway}. Services use
`@SpringBootApplication(scanBasePackages = {"com.company.leave.identity","com.company.leave.common"})` (leave: "com.company.leave.leaves").

## Common module (already written; API you can use)
- `ApiResponse<T>(success,data,meta,error)`: `ok(T)`, `page(List<T>,int page,int pageSize,long total)`, `error(code,msg,details)`; records Meta, ErrorBody, FieldError(field,message).
- `ApiException` (getStatus/getCode/getDetails) factories: badRequest(code,msg), validation(msg,details), unauthorized(msg), forbidden(msg), notFound(msg), conflict(code,msg), unprocessable(code,msg), tooManyRequests(msg) [429 RATE_LIMITED], unavailable(msg) [503], plus ctor (HttpStatus,code,msg[,details]).
- `GlobalExceptionHandler` (@RestControllerAdvice, all error shapes incl 400/404/405+Allow/500, AccessDenied 403, Authentication 401).
- `AuthUser(Long id,String employeeCode,String name,String email,String role,String department)` isHr()/isManager(). role strings "EMPLOYEE"|"MANAGER"|"HR".
- `JwtService(String secret,long accessTtlSeconds)` generateAccessToken(AuthUser), parse(token), getAccessTtlSeconds(); NOT a bean — each service's SecurityConfig declares `@Bean JwtService`.
- `CurrentUser.get()`, `CurrentUser.requireRole("HR", ...)` (throws 403 ApiException).
- `JwtAuthenticationFilter(JwtService)` (not a @Component; add to chain in SecurityConfig, before UsernamePasswordAuthenticationFilter).
- `JsonSecurityHandlers` @Component implements AuthenticationEntryPoint+AccessDeniedHandler, has `write(response,status,code,msg)` for filters.
Common deps: web, security, validation, jjwt (transitive to services).

## Global conventions
- Envelope: success `{success:true,data,meta{page,pageSize,total} lists only}`; error `{success:false,error{code,message,details[{field,message}]}}`.
- Pagination query params `page` (1-based, default 1), `pageSize` (default 20, max 100, >max => clamp to 100; <1 => 400), `sort=field,asc|desc` with whitelist (invalid field => 400 VALIDATION_ERROR). Always DB-level (Pageable/Specification), count query.
- Dates `YYYY-MM-DD`; timestamps ISO-8601 UTC (spring.jackson.serialization.write-dates-as-timestamps=false, time-zone UTC; Hibernate jdbc.time_zone=UTC).
- Unknown route 404 JSON, wrong method 405 + Allow (permit login/refresh/health paths for ALL methods in security so 405 reaches the handler), no stack traces. Security chain: stateless, csrf disabled, formLogin/httpBasic disabled, entryPoint+accessDeniedHandler = JsonSecurityHandlers. Also set `spring.mvc.throw-exception-if-no-handler-found=true`/`spring.web.resources.add-mappings=false` as needed so unknown routes give JSON 404 (also for authenticated callers; unauthenticated unknown routes => 401).
- server.shutdown=graceful, spring.lifecycle.timeout-per-shutdown-phase=20s. Fail-fast: required env has no default in application.yml (`${JWT_SECRET}` etc. so missing => startup failure with clear message); JwtService rejects <32-byte secret. `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`, Flyway migrations. Never fall back to H2.
- Logging via SLF4J only. No System.out. No blanket empty catches.
- Layering: web (controllers) / service / repo / domain / dto / config / client. Entities never exposed.
- Internal calls: header `X-Internal-Key` == env INTERNAL_API_KEY (constant-time compare). `/internal/**` is guarded by a filter in each service (missing/wrong key => 401 JSON) and is NOT routed by gateway. Internal responses are wrapped in ApiResponse.ok(data).

## Ports / env (names are binding; all must be in .env.example by orchestrator)
- identity: IDENTITY_DB_URL, IDENTITY_DB_USER, IDENTITY_DB_PASSWORD, JWT_SECRET, ACCESS_TOKEN_TTL_SECONDS, REFRESH_TOKEN_TTL_DAYS (default 7), INTERNAL_API_KEY, LEAVE_SERVICE_URL, SEED_PASSWORD (default Password@123), LOGIN_RATE_LIMIT (default 10 attempts), LOGIN_RATE_WINDOW_SECONDS (default 60), SERVER_PORT default 8081.
- leave: LEAVE_DB_URL, LEAVE_DB_USER, LEAVE_DB_PASSWORD, JWT_SECRET, INTERNAL_API_KEY, IDENTITY_SERVICE_URL, WEEKEND_DAYS (default SATURDAY,SUNDAY), BACKDATE_DAYS (7), ESCALATION_DAYS (3), UPLOAD_DIR (default ./uploads), MAX_UPLOAD_MB (5), ESCALATION_CRON (default "0 0 * * * *"), YEAR_END_CRON (default "0 10 0 1 1 *"), SERVER_PORT default 8082.
  (JWT_SECRET is used by leave only to validate; ACCESS_TOKEN_TTL_SECONDS not needed in leave -> pass a constant 3600 to JwtService ctor.)
- gateway: IDENTITY_SERVICE_URL, LEAVE_SERVICE_URL, CORS_ALLOWED_ORIGINS, SERVER_PORT default 8000.
- compose service hostnames: postgres, identity-service(8081), leave-service(8082), api-gateway(8000). DBs identity_db, leave_db (docker/postgres-init.sql).
- Tests (identity/leave) use REAL PostgreSQL: env TEST_DB_URL (default `jdbc:postgresql://localhost:5432/app`), TEST_DB_USER (postgres), TEST_DB_PASSWORD (postgres). Test isolation via dedicated schema (`identity_test` / `leave_test`): datasource url with `currentSchema=...`, flyway `schemas`+`create-schemas`, and a test FlywayMigrationStrategy bean that cleans then migrates (clean-disabled=false in test config). Test application config under src/test/resources/application-test.yml (profile "test"), providing dummy JWT_SECRET (>=32 chars), INTERNAL_API_KEY etc. Unreachable DB => test FAILS (no skips). Test-scope deps only for tests (spring-boot-starter-test, spring-security-test). No H2.

## Internal contract (agreed, identity <-> leave)
identity exposes (all `X-Internal-Key`, responses ApiResponse.ok(data)):
- GET /internal/employees/{id} -> InternalEmployee ; 404 if unknown
- GET /internal/employees/hr -> List<InternalEmployee> (active HR users)
- GET /internal/employees/by-manager/{managerId} -> List (active direct reports)
- GET /internal/employees?ids=1,2,3 -> List ; GET /internal/employees?department=X -> List (active)
InternalEmployee JSON: {id, employeeCode, name, email, role, department, managerId, active}
leave exposes: POST /internal/balances/init body {employeeId, dateOfJoining:"YYYY-MM-DD"} -> 200 ApiResponse.ok(list of BalanceDto). Idempotent; creates current-year balances for every active leave type, pro-rated by joining date if joined in current year (total = quota * remainingMonthsInclusive/12 rounded to 0.5; unlimited type: total 0). If joined in prior year: full quota.
Gateway routing: leave-service paths: /api/v1/employees/*/leave-balances/** (ordered FIRST), /api/v1/leave-balances/**, /api/v1/leave-requests/**, /api/v1/leave-types/**, /api/v1/holidays/**, /api/v1/reports/**, /api/v1/notifications/**, /api/v1/dashboard/**, /api/v1/team/**; identity: /api/v1/auth/**, /api/v1/employees/**.

## Auth DTO contract (identity)
- POST /auth/login {identifier,password} -> data {token, accessToken(same), refreshToken, expiresIn(seconds), user}
- POST /auth/refresh {refreshToken} -> same shape; rotates (old revoked). POST /auth/logout {refreshToken?} -> revokes presented token (or all of caller's). POST /auth/change-password {currentPassword,newPassword(min 8)} -> 200. GET /auth/me -> user.
- user = {id, employeeCode, name, email, role, department, designation, dateOfJoining, manager:{id,employeeCode,name}|null, active}
- Errors: bad creds 401 INVALID_CREDENTIALS; inactive 403 ACCOUNT_INACTIVE; rate limit 429 RATE_LIMITED (+Retry-After).
JWT claims: sub=id, code, name, email, role, department.

## Seed (identity DataSeeder; password SEED_PASSWORD default Password@123)
HR001 hr@company.com (HR, dept "Human Resources", no manager); MGR001 manager1@company.com, MGR002 manager2@company.com (MANAGER, "Engineering", no manager); EMP001..003 emp1..3@company.com under manager1; EMP004..005 emp4..5 under manager2 (EMPLOYEE, "Engineering"). dateOfJoining 2023-01-02-ish (prior year => full quotas). Seeder calls leave init in a BACKGROUND retry loop (e.g. 30 x 2s) so startup is not blocked; executor shut down on context close.
Leave types seeded by leave-service Flyway V2 migration (so they exist before any request): CL Casual 12 paid halfDay carryFwd no; SL Sick 10 paid halfDay attachmentRequired=true attachmentRequiredAfterDays=2 (backdating allowed; minNotice 0); EL Earned/Privilege 15 paid carryForward max 30 halfDay; ML Maternity 182; PL Paternity 10; CO Compensatory Off (quota 0 — adjusted by HR); LOP Loss of Pay unlimited=true paid=false. Holidays: DataSeeder (ApplicationRunner, idempotent) for current year.

## Leave rules summary (leave-service)
See task spec in orchestrator prompt; key: statuses PENDING_MANAGER, PENDING_HR, APPROVED, REJECTED_BY_MANAGER, REJECTED_BY_HR, CANCELLED; stages MANAGER|HR|NONE. Error codes: VALIDATION_ERROR 400 (from>to, zero working days, missing attachment, backdate, min notice, half-day rules, missing comment on reject), OVERLAP 409? -> use 409 `LEAVE_OVERLAP`; INSUFFICIENT_BALANCE 422; INVALID_TRANSITION 409; 403 FORBIDDEN not yours / self-approval; 404 NOT_FOUND.
Apply endpoint: POST /api/v1/leave-requests accepts multipart (part `data` JSON or flat form fields, + part `attachment`) OR application/json; returns 201 with LeaveRequestDto. attachmentUrl = `/api/v1/leave-requests/{id}/attachment`.

## Status
| Step | Status |
|---|---|
| parent pom, common, .gitignore | DONE |
| identity-service | DONE — 45 tests pass (mvn -pl identity-service test, real PG, schema identity_test). Extras: GET /health; AppProperties (@ConfigurationProperties "app"); filters LoginRateLimiter/InternalKeyFilter are NOT beans (chain only); /api/v1/employees/** also guarded hasRole(HR) in chain; refresh invalid => 401 INVALID_REFRESH_TOKEN; wrong current password => 400 INVALID_CURRENT_PASSWORD; HR self-deactivate => 400 CANNOT_DEACTIVATE_SELF; demote manager w/ reports => 409 MANAGER_HAS_REPORTS; logout requires bearer. Dockerfile at identity-service/Dockerfile (context = repo root, copies all module poms). Not run: docker build, real boot. |
| leave-service part A (infra/domain/balances/masters) | DONE (32 tests green vs real PG: WorkingDayCalculatorTest 10, BalanceServiceTest 15, MasterDataApiTest 7). Extra config keys: leave.jwt-secret/internal-api-key/identity-url (LeaveProperties), leave.seed-holidays (default true; false in test). Test base: com.company.leave.leaves.AbstractIntegrationTest (+TestFlywayConfig). Dockerfile at leave-service/Dockerfile, context=repo root. Part B may add repo methods; LeaveRequestRepository has lockById/findOverlapping/findInRange*/findOnLeave*. |
| leave-service part B (workflow/query/reports/dashboard/tests) | DONE — 75 tests green vs real PG (ApprovalWorkflowTest 20, PermissionTest 18, AttachmentStorageTest 5 + Part A 32); `package -DskipTests` OK. Endpoints: /api/v1/leave-requests (POST json|multipart, /preview, /me, /pending-for-manager, /team, /pending-for-hr, GET "" HR, /{id}, /{id}/attachment, /{id}/cancel, /{id}/manager-action, /{id}/hr-action), /api/v1/reports/{summary,on-leave,export}, /api/v1/team/calendar, /api/v1/dashboard. GET /leave-types and /holidays are now paged (meta, default pageSize 50, `sort`). Notes: attachments validated by ext+content-type+magic bytes; per-employee pg advisory lock serialises applies; min-notice only enforced when type.minNoticeDays>0; complementary half-days (FIRST/SECOND) on one day don't overlap; DashboardDto.upcomingLeaves is List<LeaveRequestDto>; report export extra optional params leaveTypeId,status. Gateway needs no change (paths already routed); multipart max size = MAX_UPLOAD_MB (spring.servlet.multipart) — gateway max-in-memory-size should be >= that. |
| api-gateway | DONE (16 tests green, jar builds; prefix spring.cloud.gateway.server.webflux; Boot 3.5 codec key spring.http.codecs.max-in-memory-size; fail-fast via RequiredEnvListener; Dockerfile at api-gateway/Dockerfile, context=repo root) |
| docker-compose, Dockerfiles, .env.example, README, openapi | DONE — compose uses postgres:17.2 (not published; only gateway 8080), `docker compose config -q` OK, `docker compose build identity-service leave-service api-gateway` all built OK; docs/openapi.yaml parses (35 paths). Compose has dev defaults for every var so it works without .env. |
| mvn verify + boot verification + curl probes | DONE — `mvn -B verify` (docker maven:3.9.9-eclipse-temurin-21, real PG): identity 45, leave 75, gateway 16 tests, 0 failures, BUILD SUCCESS. Booted all 3 jars against real PG via gateway: /health 200; login 200; bad JSON 400 JSON; unknown route 404 JSON; /auth/me no token 401 JSON; DELETE /auth/login 405 + Allow: POST; /internal via gateway 404; full flow emp1 apply -> manager approve -> HR approve (balance used 2.0), notifications, employees list (HR) / 403 for emp, balances, dashboard, summary report, CSV export (text/csv), calendar, holidays, create employee (balance init through identity->leave) all OK; SIGTERM shutdown clean (JVM exit 143, no ERROR in logs). NOT VERIFIED: `docker compose up` of the full stack (build only), test DB must be reachable via TEST_DB_URL for mvn verify. |
| Verifier fixes round 2 | DONE — gateway now listens/publishes on 8000 (compose `${GATEWAY_PORT:-8000}:8000`, SERVER_PORT=8000, healthcheck, Dockerfile EXPOSE, .env.example, README). Gateway serves Swagger UI at GET /docs (swagger-ui webjar 5.17.14, no CDN), spec at /openapi.yaml, /openapi.json, /v3/api-docs (bundled from docs/openapi.yaml via pom resource; JSON converted at startup); ApiDocsController + ApiDocsTest. Evidence: gateway tests (`mvn -pl api-gateway -am test`): ApiDocsTest 3, GatewayRoutingTest 12, DownstreamDown 3, FailFast 1 — 0 failures; `docker compose build api-gateway` OK. NOT VERIFIED: full-stack `up` on 8000 (verifier probes). |
