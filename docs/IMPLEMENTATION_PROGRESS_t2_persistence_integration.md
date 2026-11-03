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

## Reservation and persisted-result stabilization (2026-11-02)

- **PARTIAL — RESERVATION / T-01, T-05~T-07:** customer list/cancel and guarded reschedule routes exist with ownership, Case eligibility, 24-hour checks, version checks, and audit events. Create/reschedule fail closed with `RESERVATION_CAPACITY_FLOW_UNAVAILABLE`: current Flyway V1–V11 lack canonical `branch_slot`, `staff_slot_availability`, and related `visit_reservation` references; the slot capacity and employee assignment transaction is not implemented. The simplified direct-confirm path was removed; no unbacked booking is reported as confirmed.
- **IMPLEMENTED — B-02 result restore:** added `GET /api/v1/customer/b02/work-items/{workItemId}/result`. It checks JWT customer ownership and B-02 task code, returns only a completed persisted `task_result`, and rejects unfinished or foreign work items. The React B-02 route restores the completed state from that result on re-entry.
- **VERIFIED:** `VisitReservationMySqlIT` + `VisitReservationPolicyTest`: 4 passed; migrations V1–V11 applied on disposable MySQL 8.0.36. Tests prove reservation creation/reschedule are rejected without canonical capacity schema, cancellation checks ownership and the 24-hour cutoff, and V10 active Case/employee-slot generated keys reject duplicates then release after cancellation while preserving history. They do not verify a successful customer booking API because the required canonical slot schema is not migrated.
- **VERIFIED:** `B02CustomerBffControllerTest` + `B02StoredResultMySqlIT`: 4 passed, including JWT-derived customer ID, completed result retrieval from MySQL, foreign-owner denial, and active/non-B-02 rejection. The MySQL test applied V1–V11.
- **VERIFIED:** React tests: 12 passed; `npm run build` succeeded. Initial sandbox attempt hit Windows Vite `spawn EPERM`; retry with approved elevated execution succeeded. `git diff --check` succeeded.
- **VERIFIED WITH KNOWN FAILURE:** full Maven suite ran 172 tests, 0 errors, 3 skipped, 1 failure. Sole failure is team-owned `ChatPersistenceMySqlIT.flywayV1ThroughV10AndChatRoomOwnershipQueriesWork`: it asserts latest Flyway version `10`, while current schema correctly reaches V11. The Chat test was not modified.
- **NOT RUN:** browser GUI E2E, live CLOVA/OCR, SMS, and real financial services. No external calls were made.
- **OPEN TEAM CONTRACT:** canonical `staff_task_capability` is in `docs/01_schema_mysql8.sql` but missing in V1–V11. T1/Flyway owner must assign and approve a migration before reservation candidate filtering can operate in a production schema. Chat/WorkflowStart still needs to pass trusted `workItemId` into A-01/B-02 task routes; this branch did not change T1-owned navigation or Workflow code.

## Reservation demo scope follow-up (2026-11-02)

- Status: IMPLEMENTED; selected MySQL/Testcontainers and React checks VERIFIED.
- Changed reservation path to save customer booking requests as `PENDING_ASSIGNMENT`; staff assignment is not inferred or exposed as confirmed.
- Added JWT-bound eligible Case and active branch reads. Eligibility requires current-revision staff `PROCEED`, `REVIEW_PASSED`, `visit_required`, and `result_notified_at`.
- Serialized same-branch booking changes with branch-row locks; conservatively permits one active request per branch and start instant until canonical capacity slots are supplied. Request payload no longer accepts a staff ID.
- Existing reservation cancellation/rebooking and 24-hour policy retained. Reschedule returns to `PENDING_ASSIGNMENT`.
- UI reference files under `docs/ui-reference/` were absent; the React page uses explicit pending-assignment copy and does not show confirmation before server state.
- Tests: Java compile passed; Maven reservation-focused suite passed (10 tests; includes fresh MySQL 8.0.36 Flyway V1-V11 and 8 reservation DB/API scenarios); React tests passed (12); React production build passed; `git diff --check` passed at initial implementation check.
- Remaining: canonical branch-slot capacity, office hours, staff availability/assignment, staff reservation work queue authorization, and visual comparison to the missing UI artifacts. Branch-row locking protects this API flow; direct SQL/out-of-band writers are not covered by a branch-time UNIQUE constraint.
- Additional regression: `B02CustomerBffControllerTest` and `B02StoredResultMySqlIT` passed (4 tests total; isolated MySQL 8.0.36 and Flyway V1-V11 for the stored-result IT).

## Reservation JWT and re-entry stabilization (2026-11-02)

- **IMPLEMENTED:** Removed premature `@ConditionalOnBean(JdbcTemplate.class)` gating from the customer reservation BFF and reservation controller. Under the full Spring test context those mappings were absent and returned 404 even though the JDBC infrastructure existed.
- **IMPLEMENTED:** Reservation responses now derive and return `workItemId` and `taskCode` through the existing `visit_reservation -> service_case -> work_item` relationship. Eligibility reads the canonical WorkItem task code and requires it to match the Case type; create/reschedule re-check supported C WorkItem linkage. No schema or migration changed.
- **IMPLEMENTED:** Customer reservation UI labels `PENDING_ASSIGNMENT` as “예약 요청 접수 · 직원 배정 대기” and explicitly says the visit is not confirmed. The page fetches the reservation list on mount/refresh; server state is the source for re-entry.
- **VERIFIED:** `CustomerReservationJwtMySqlHttpIT`, `VisitReservationMySqlIT`, `VisitReservationPolicyTest`: 11 tests passed on isolated MySQL 8.0.36 with Spring Flyway V1-V11. Signed customer login was used; tests cover create/list-after-reload/reschedule/cancel/rebook, Case/WorkItem/taskCode response linkage, foreign-owner 404, unauthenticated 401, 23-hour cutoff rejection, pending/unassigned state, branch-time concurrent conflict (one 200 and one 400), and existing SQL/DB reservation regression cases.
- **VERIFIED:** React tests: 14 passed; production build passed. `git diff --check` passed.
- **OCR GAP CONFIRMED:** Spring verifies the current Case revision and file/document linkage before persisting OCR and passes current-revision `ocrExtractions` in the Staff Agent snapshot. It does not compare `grantor_names` or `agent_name` against Case/heir values. FastAPI `app/tasks/c02/checks.py` currently checks required-document metadata and does not consume those OCR field values. No legal identity/rights comparison is implemented.
- **CHAT HANDOFF GAP:** `taskPathForWorkflow` maps server-issued ACTIVE task results, and `chat.ts` models `AgentResult.workflow`/`executionStatus`, but repository search found no UI caller of the navigation helper. `WorkflowStartController` remains an internal token-protected tool API. Team 1 must connect the authenticated Chat result to the existing helper and pass the server-issued `taskCode + workItemId + workItemStatus`; the browser must not call WorkflowStart or invent IDs.
- **NOT RUN:** external TCP/browser GUI E2E. This Testcontainers test exercises the Spring security/filter/controller HTTP dispatch via MockMvc, but does not launch a user browser or Vite against a running authenticated backend.
- **DECISION PENDING:** no staff reservation queue API was added. Staff-wide access to customer reservations is not specified; current rows are also unassigned, so a staff list needs an agreed branch/role scope and visibility contract first.

## C-02 Mock OCR structured comparison (2026-11-02)

- **IMPLEMENTED:** Added deterministic Spring comparison for only `ABSENT_HEIR_POWER_OF_ATTORNEY` on the Case's current revision. It verifies the current file/revision/document slot, `required_document.heir_id`, matching `heir.case_id/person_ref`, and compares normalized `grantor_names` with persisted `heir.name`.
- **IMPLEMENTED:** `agent_name` is `UNVERIFIABLE` when extracted because C-02's `DOCUMENT` delegation model has no explicit agent-name reference. No name or relationship is inferred. Missing/blank/invalid-confidence/low-confidence fields, failed OCR, and unlinked references remain manual-review cases; `NOT_APPLICABLE` is excluded.
- **IMPLEMENTED:** Mock adapter emits the same normalized `fields[]/{name,value:{text,confidence}}` structure as CLOVA Template output and labels it `SYNTHETIC_MOCK_FIXTURE`. Synthetic fixture values are property-configured and are never copied from runtime Case data. Provider default remains Mock; no external OCR call or migration was added.
- **IMPLEMENTED:** Staff OCR response now includes extracted/reference values, candidate state, confidence, reason, and a manual source-check flag. Customer OCR response includes no comparison values. Staff Agent snapshot receives only structured validation state/reason/confidence and no extracted names; FastAPI translates mismatch/manual/unverifiable states into `SPRING_RULE` review items. Agent cannot decide the Case.
- **VERIFIED:** Java compile passed. Targeted Maven command `-Dtest=MockOcrAdapterTest,OcrPersistenceTest,C02StaffPrecheckMySqlHttpIT test` passed 11 tests (0 failures/errors): MySQL 8.0.36 Testcontainers applied Flyway V1-V11; signed customer upload persisted synthetic extraction; staff OCR API returned comparison; Agent snapshot omitted extracted names; scenario matrix covered match, mismatch, missing/blank, invalid and low confidence, absent reference data, OCR failure, NOT_APPLICABLE, stale revision, and customer no-comparison response.
- **VERIFIED:** Targeted FastAPI OCR-validation unittest (2 tests) and `compileall -q app` passed. Full FastAPI unittest discovery ran 23 tests; 19 passed and 4 unrelated existing LangGraph-dependent C-01/C-02 tests failed because the active interpreter lacks `langgraph` (one import error, three assertions against the fallback graph).
- **VERIFIED:** Staff React page renders candidate/reference/confidence/reason and explicitly marks Mock data as synthetic. Vitest: 15 tests passed across 5 files; TypeScript/Vite production build passed.
- **LIMIT:** Comparison is a conservative text candidate, not identity, signature, document authenticity, legal delegation, or inheritance-right verification. No explicit agent-name Case reference exists for this document type. Official PDF/CLOVA behavior remains unverified.

## A-01 / B-02 JWT + MySQL vertical verification (2026-11-03)

- **IMPLEMENTED:** Added `backend/src/test/java/com/bankingax/task/a01/A01B02CustomerJwtMySqlHttpIT.java`. It seeds synthetic users, Workflows, WorkItems, accounts, and transactions in an isolated MySQL 8.0.36 Testcontainer, then obtains customer JWTs through `/api/v1/auth/login` and exercises the customer BFFs with MockMvc.
- **VERIFIED — A-01 / FLOW-03, FLOW-11:** actual login and `/auth/me`; authenticated transaction query returned two synthetic MySQL rows; owned WorkItem 7111 became `DONE`, Workflow 7151 became `COMPLETED`, and one `TRANSACTION_SEARCH` task_result contains the saved response. Repeating the identical POST/requestId replayed the saved response without a duplicate result. Anonymous access returned 401; a foreign account returned 403 without completing its WorkItem; another customer's JWT could not use the WorkItem.
- **VERIFIED — B-02 / SIGN-03, SMS binding, task_result:** actual login; prepare returned B2-R3; consent was persisted; the e2e-profile-only Mock OTP (000000) was issued, wrong OTP and pre-verification execute were rejected without account/Workflow/result/event mutation; valid verification returned a binding for WorkItem 7121, distinct from account 7141. Execute closed only the synthetic account (version 0→1), changed WorkItem to `DONE` and Workflow to `COMPLETED`, and wrote one task_result (`decision_code=B2-R3`, `rule_version=1.7.1`, before/after JSON), consent row, and event_log row. Same request replay returned the stored receipt with one result/event; a new requestId after completion was rejected. Foreign-account/foreign-WorkItem/result and anonymous result access were rejected.
- **VERIFIED:** MySQL Testcontainers applied Flyway V1-V11 and Spring JPA initialized. Focused Maven command covering A-01/B-02 unit, BFF, HTTP, stored-result and JDBC adapter tests passed **23 tests, 0 failures/errors/skips**, including the new combined JWT/HTTP/MySQL IT. `npm test`: 15 tests passed in 5 files; `npm run build` passed; `git diff --check` passed (only existing LF/CRLF notices).
- **LIMIT / T1 handoff:** WorkItems in this IT are seeded against valid MySQL Workflow/WorkItem schema because `WorkflowStartController` is an internal gateway-token tool route, not a customer JWT BFF. Chat → authenticated workflow creation/navigation was not tested or modified. A-01 has no separate GET stored-result endpoint; the tested restore mechanism is replaying the same POST with the same requestId and payload. Browser refresh/GUI state restoration was not run. B-02 has a completed-result GET, exercised with the real customer JWT.
- **ENVIRONMENT:** the restricted execution first denied Docker named-pipe access and Vite's Windows `spawn` with `EPERM`; reruns outside the restricted sandbox connected to Docker Desktop and ran Vitest successfully. Only disposable Testcontainers were used; no personal DB or persistent Docker volume was modified. No live banking, SMS, or external service calls were made.

## 2026-11-03 — A-01/B-02/C-02 화면 재진입 검증

- 기능 ID: A-01 재조회, B-02 완료 결과 재진입, C-02 최신 Case snapshot 재로드.
- 변경: `A01WorkflowService.result` 및 JWT 경계 `GET /api/v1/customer/a01/work-items/{workItemId}/result`; `A01CustomerBffControllerTest`, `A01B02CustomerJwtMySqlHttpIT`; `taskApi.getA01Result`, `TransactionSearchPage` 저장 결과 복원; `C02IntakePage.reloadCaseData`가 서버 최신 snapshot/document/precheck/OCR를 다시 읽고 revision 일치를 검사.
- 검증: Maven `A01B02CustomerJwtMySqlHttpIT,A01CustomerBffControllerTest` 5 tests PASS; A-01/B-02/C-02 선별 회귀는 Testcontainers 등 29 tests 중 기존 WebMvc fixture의 미인증 기대값 1건을 확인했고 fixture에 맞춰 정정 후 A-01 두 클래스를 재실행해 통과. React Vitest 15 tests PASS, `npm run build` PASS.
- 제한: GUI 브라우저 실행은 하지 않음. WorkItem 생성/완료 task의 채팅 재진입 링크와 고객별 진행 Case 목록은 T1 Workflow/Chat 계약이 필요. A-01은 저장 결과 GET을 추가했지만 페이지네이션은 완료된 WorkItem의 저장된 단일 페이지 범위.

## Customer T2 task history and re-entry (2026-11-03)

- **IMPLEMENTED:** Added `GET /api/v1/customer/task-history`, deriving the customer only from the validated JWT subject. A read-only query uses existing `workflow.customer_id -> work_item.workflow_id`, with `service_case.work_item_id/customer_id` for C-02 and `task_result.work_item_id/result_type` for completed A-01/B-02. It returns only T2 task codes, status, Case ID/status when present, and a result-availability flag; no customer/account PII or result payload is included.
- **IMPLEMENTED:** Added T2 history DTO/client, a customer history page and `taskHistoryPath`. ACTIVE WorkItems reuse the existing `taskPathForWorkflow`; saved A-01/B-02 results use `?view=result`; C-02 re-entry uses the Case ID returned by the API. A-01/B-02 pages now prevent mutation in result-only mode and restore from existing result GET APIs. No schema or Flyway change.
- **VERIFIED:** `A01B02CustomerJwtMySqlHttpIT` passed on an isolated MySQL 8.0.36 Testcontainer; Flyway V1-V11 applied. Actual customer JWT calls returned only that customer's four seeded T2 WorkItems; completed A-01/B-02 were marked result-available, C-02 returned its linked Case ID, another customer's JWT saw only its own WorkItem, and anonymous access returned 401. The same JWT loaded the linked C-02 snapshot at revision 0; another customer received 403 for that Case. The IT also re-ran the existing A-01/B-02 business flow.
- **VERIFIED:** React tests: 19 passed in 6 files; TypeScript/Vite production build passed. `git diff --check` passed (Git reported existing LF/CRLF conversion notices only).
- **T1 ROUTE HANDOFF:** `CustomerTaskHistoryPage` and links are implemented, but route registration and the currently disabled MainPage history tile are in T1's shared shell ownership. Required integration: add authenticated `/tasks/history` route and enable/link the “업무 요청 기록” tile; no `taskPathForWorkflow` change is needed. Browser navigation to the new page remains unavailable until this shared route/link is registered.
- **NOT RUN:** Real browser GUI flow and T1 Chat -> authenticated workflow -> task history/navigation handoff.

## T2/T1 integration handoff (2026-11-03)

- **IMPLEMENTED:** Added `docs/T2_T1_통합_계약_및_브라우저_검증절차.md` with server-issued Chat/Workflow ID contract, task-history route/link handoff, C-02 staff/customer and reservation sequence, and a manual browser procedure.
- **FIXED:** `CustomerTaskHistoryService` now resolves JDBC lazily so non-persistent WebMvc profiles can start; the task-history endpoint returns `TASK_HISTORY_UNAVAILABLE` if called without the JDBC persistence bean. This avoids making unrelated A-01/B-02/C-02 fixture contexts fail during startup.
- **VERIFIED:** Focused Maven run after this fix passed 25 tests, including A-01/B-02 JWT/MySQL, stored B-02 results, C-02 staff precheck, reservation JWT/MySQL, B-02 HTTP and C-02 staff-decision HTTP tests. React Vitest: 19 passed; production build passed. FastAPI unittest discovery: 23 passed. `git diff --check` passed.
- **FULL SUITE:** Post-fix full Maven run: 185 tests, 1 failure, 0 errors, 3 skipped. The only failure is T1-owned `ChatPersistenceMySqlIT.flywayV1ThroughV10AndChatRoomOwnershipQueriesWork` expecting max version 10 while V11 is applied. The two no-JDBC context errors seen before the fix are gone.
- **REMOTE / HANDOFF:** `origin/main=d913a018` after T1 PRs #34–#36; `origin/feat/t2-browser-e2e=1ed48aa` after ordinary commits were pushed. The branch remains unmerged from current main (11 main-only / 11 T2-only commits); no rebase, merge, PR creation, or PR #31 change was made. Existing PR #31 head `b6cf9fd` is an ancestor of this follow-up branch. T1's `/work` currently uses a Mock adapter; use this document's handoff contract to connect that shared UI to the authenticated task-history BFF without duplicating navigation.
