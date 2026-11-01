# T2 persistence integration progress

## Scope and guardrails

- Branch: `feat/t2-persistence-integration`; no commit, push, merge, reset, or existing database mutation.
- Existing C-02 V5 work is retained.
- Verification uses unit/HTTP tests and disposable MySQL containers only.

## JWT PR synchronization (2026-11-01)

- **IMPLEMENTED:** imported the JWT/refresh-token Spring and React source from `origin/main` commit `58b1bfc`; task routes now use the existing CUSTOMER/STAFF React route guard.
- **IMPLEMENTED:** main-owned `V5__create_refresh_token.sql` and `V6__create_chat_message.sql` are retained. Unmerged T2 migrations are now V7 (C-02 review/notification), V8 (A-01/B-02 finance/consent), V9 (OCR/reservation), and V10 (active reservation constraints).
- **VERIFIED:** a clean disposable MySQL 8.0.36 received V1–V10 through Spring Flyway; JPA and Tomcat initialized. A prior private database that recorded the old T2 V5–V8 filenames must not be pointed at this source without an approved history migration plan, because Flyway checksum/version history differs.
- **BLOCKED:** JWT Security grants existing Tool, B-02, and C-02 controllers only `ROLE_TOOL`. No session-to-tool BFF exists, so `taskApi.ts` remains disabled and does not send a browser token as an internal credential.

## Phase status

| Phase | Status | Notes |
| --- | --- | --- |
| A — B-02 Workflow | IN PROGRESS | V6 now provisions the canonical Mock A-01/B-02 finance tables, `consent_record`, and missing V4 task-result fields. WorkItem-bound OTP contract remains the next implementation unit. |
| B — A-01 Workflow | PENDING | Start after B-02 vertical path and regression tests. |
| C — C-02 review/revision | IN PROGRESS | V5 and DB decision persistence exist; revision/review restoration remains to be completed. |
| D — reservation/recommendation | PENDING | Requires a minimal existing/common reservation contract review. |
| E — OCR | PENDING | Mock-first; no real document transmission. |
| F — UI/E2E | PENDING | Must not expose internal gateway credentials in a browser. |

## Executed verification

- C-02 non-DB suite: 16 tests passed.
- Disposable MySQL 8.0.36: V1–V5 SQL and representative staff-decision persistence transaction validated.
- Java Testcontainers is currently blocked before container start by the Docker Desktop named-pipe API response; this is recorded as environment-only, not a code-test failure.

## Coordination points

- Do not change common authentication/BFF, SMS, customer-intent, A-02, B-01, C-01, or the base Workflow owner API without an explicit contract.
- B-02 and A-01 integrations should use additive task-specific persistence services until the common Workflow owner accepts a shared transition API.

## Latest implementation

- A-01: `A01WorkflowService` now locks and validates the active transaction-search WorkItem, persists the JSON query result in `task_result`, completes the WorkItem, and updates Workflow status. `ToolGatewayController` chooses it only in JPA Workflow mode; the existing non-persistent query path remains compatible.
- B-02: Controller and FastAPI gateway now forward the actual WorkItem ID into the approved Workflow-aware execution path.
- Remaining C-02 revision/OCR, shared notification, reservation/recommendation, and browser-safe UI work remain unimplemented in this branch.

## Integration verification update (2026-11-01)

- **VERIFIED — JDBC/Flyway:** Windows host port `3307` answered as MariaDB 11.4.12 although the Docker container itself was MySQL 8.0.36; this produced Connector/J `BadAuthenticationPlugin` before Spring could start. A dedicated disposable MySQL 8.0.36 mapping on `13307` returned `caching_sha2_password`; Connector/J 8.3.0 DriverManager and Spring Hikari both connected successfully.
- **VERIFIED — Spring schema boot:** Spring Flyway applied V1–V8 on the disposable database and `flyway_schema_history` has eight successful rows. JPA initialized and Tomcat started on port 18080.
- **IMPLEMENTED — C-02 boot fix:** Removed the `C02IntakeService` ↔ `C02HeirDocumentProvisioningService` bean cycle by making required-document derivation static; no circular-reference setting was enabled.
- **VERIFIED — regression:** `C02IntakeServiceTest`, `C02ControllerSmokeTest`, and `C02StaffDecisionHttpTest`: 13 passed.
- **BLOCKED — browser business calls:** the existing React client correctly sends same-origin cookies only, but no common authenticated BFF maps a session to `X-Actor-Id`, `X-Actor-Role`, and the server-only `X-Tool-Gateway-Token`. No browser token bypass was added.

## UI vertical-integration update (2026-11-01)

- **IMPLEMENTED — BFF-safe task client:** `frontend/src/api/taskApi.ts` defines same-origin A-01, B-02, C-02, notification, staff-review, and reservation requests. Calls require `VITE_BFF_TASKS_ENABLED=true`; no actor header, customer identifier, or internal token is emitted by the browser.
- **IMPLEMENTED — screens:** A-01 result/paging/error UI, B-02 prepare/OTP/execute state UI, C-02 preparation UI, staff-decision UI, customer notification/read UI, and reservation list/create/cancel UI use that client and show an explicit waiting state when the BFF is absent.
- **VERIFIED — build:** React `npm run build` succeeded after the task-client integration; FastAPI `python -m compileall -q app` and Spring Maven `compile` succeeded.
- **BLOCKED — browser E2E:** computer-use browser host is unavailable in this environment. No GUI result or authenticated business API execution was claimed. Common BFF must proxy/authorize the same-origin task paths and derive customer/staff identity from its session before `VITE_BFF_TASKS_ENABLED` is enabled.

## JWT task BFF integration update (2026-11-01)

- **IMPLEMENTED:** Spring JWT task BFF controllers expose customer A-01, B-02, C-02, notification, and reservation routes plus STAFF C-02 review/decision routes. They derive the actor from `JwtAuthenticationToken`, use the server-only Tool Gateway token, and delegate to existing controllers/services.
- **IMPLEMENTED:** `/api/v1/staff/**` and `TaskBffPrincipal` require canonical JWT authority/claim `EMPLOYEE`; the database and React API label for that user remains `STAFF`.
- **VERIFIED:** selected JWT/BFF/B-02/C-02 Maven suite: 28 tests, 0 failures/errors. BFF tests cover identity derivation, cross-role denial, spoofed customer identity, and separate B-02 account/WorkItem forwarding.
- **VERIFIED:** on a fresh disposable MySQL 8.0.36 database, Spring applied V1--V10, JWT login succeeded, and customer A-01 BFF returned one transaction. DB state: WorkItem `DONE`, Workflow `COMPLETED`, one `task_result`.
- **VERIFIED:** React `npm run build` succeeds with BFF routes. `VITE_BFF_TASKS_ENABLED` remains false by default.
- **PARTIAL:** no browser GUI host was available. B-02/C-02 production-like DB fixtures (OTP and full Case/staff setup) remain live-DB E2E work; their BFF contracts are controller/fixture tested.
- **COMMON CONTRACT:** Chat must create and return the customer-confirmed WorkItem on a trusted server hop before navigating a task page. Browsers never call internal workflow-start or send actor IDs/gateway credentials.
- **VERIFIED:** a disposable MySQL-backed `STAFF` user logged in through `/api/v1/auth/login` with JWT claim `EMPLOYEE`; `/api/v1/auth/me` returned API role `STAFF`. The employee JWT passed the C-02 staff BFF security boundary (404 for a deliberately absent Case), while a customer JWT received 403 and an anonymous request received 401.

## BFF contract and regression update (2026-11-01)

- **IMPLEMENTED — handoff contract:** `docs/contracts/core/bff-task-session.md` records the browser-to-internal DTO transformations, role checks, identifier rules, envelope/error preservation, and the safe activation criterion for `VITE_BFF_TASKS_ENABLED`.

- **BLOCKED — common BFF:** no server-side login/session/BFF implementation is present in the current tree. `taskApi.ts` must remain disabled until it exists.
- **Required BFF transformation:** derive customer/staff identity from the signed session; reject any client-supplied actor identity; inject `X-Actor-Id`, `X-Actor-Role`, and the server-only gateway token only on its internal hop. For A-01, inject FastAPI `customerId`; for B-02 inject Spring `customer` and preserve the separate `account`/`workItemId`; for C-02 inject `customerId` into prepare/upload/read bodies or query parameters. Staff endpoints must require a STAFF session.
- **VERIFIED — regression:** 21 tests passed: B-02 service/HTTP, C-02 intake/controller/upload/staff-decision HTTP and E2E fixture tests.
- **Fix:** JDBC-only notification, reservation, OCR, and C-02 review-persistence beans are now conditional on `JdbcTemplate`, preserving DB persistence in production while allowing existing in-memory HTTP fixtures to boot.
- **BLOCKED — Python pytest:** the active Python interpreter has no `pytest` module; `compileall` is the completed Python validation.
