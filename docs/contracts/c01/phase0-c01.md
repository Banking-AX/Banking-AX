# C-01 G1 contract boundary

Owner: T1. G1 uses an in-memory Spring adapter; JPA persistence and actual T2 document storage remain outside this slice.

## G1 customer intake API

The customer Agent calls `POST /api/v1/c01/start` through the T1 Tool Gateway with `customerId`, `workflowId`, `expectedWorkflowVersion`, `requestId`, `loanId`, and `registeredPhone`. Spring owns the selected-loan ownership check, creates an active `DEBT_ADJUSTMENT` WorkItem and a `DRAFT` Case, and returns the Case revision. The same `requestId` with exactly the same start input returns that original draft; a reused request ID with different input is rejected. `PUT /api/v1/c01/{caseId}/intake` stores the required reason, current situation, repayment capacity, and a **Fake DocumentPort slot result**.

All C-01 endpoints in this Fake slice require `X-Tool-Gateway-Token`, `X-Actor-Id`, and `X-Actor-Role: CUSTOMER`; Spring rejects a token failure, an actor/customer mismatch, or a non-customer role before calling the service. This is a development-only internal Gateway boundary, **not** completed end-user principal authentication. The body `customerId` remains an input for ownership revalidation and must not be trusted by a public customer-facing endpoint until authenticated-principal integration exists.

`POST /api/v1/c01/{caseId}/consent` records required consent and only a mock-signature completion flag. It accepts no stroke or image. `POST /api/v1/c01/{caseId}/verification/issue`, `POST /api/v1/c01/verification/verify`, and `POST /api/v1/c01/{caseId}/submit` use `C01_SUBMIT` bindings. Submit requires the same customer, WorkItem, action, and revision and consumes the binding once. Same `requestId` returns the same result only for the same Case/revision/customer; conflicting reuse is rejected.

`C1-R1` through `C1-R4` are Java blocking classifications. `C1-R5` and `C1-W1` through `C1-W3` produce staff-review items, never a final decision. The source value for the mock `C1-R2` threshold is `loan.principal_amount`; whether the business policy means original or outstanding principal is **[확인 필요]**. No visit reservation is created by this API.

## T2 staff check registration

T1 adds `app.tasks.c01.register_c01_check(registry)` without changing T2's `staff_engine`. Register it with `CheckRegistry` before invoking `StaffCheckExecutor`. The handler accepts the existing `register_check` `CheckRequest` and returns only `reviewItems`. `adapt_c01_response` maps a C-01 `NEEDS_CUSTOMER_CONFIRMATION` item to `WAITING_CUSTOMER`; this adapter is needed because the common executor currently always emits `READY_FOR_STAFF` for successful handlers.

The CasePort input identifies `DEBT_ADJUSTMENT`, work item, selected loan, applicant fields, and current application revision. Submit requires a valid C01 verification binding plus consent and rules revalidation when the real API is added. Spring moves the Case to `SUBMITTED` and requests an asynchronous T2 `register_check` using `(caseId, revision, taskCode, input)`.

The T2 check result may set only the Agent review axis (`PENDING`, `RUNNING`, `WAITING_CUSTOMER`, `READY_FOR_STAFF`, `FAILED_NEEDS_STAFF`) through the Spring boundary. Its run `status` is independently `SUCCEEDED` or `FAILED`; it cannot decide `PROCEED`, create a reservation, or write final financial results.

Phase 0 Fake result:

```json
{"caseId":2001,"revision":0,"status":"SUCCEEDED","reviewStatus":"READY_FOR_STAFF","reviewItems":[{"source":"SPRING_RULE","result":"STAFF_CONFIRMATION_REQUIRED","evidence":[]}],"error":null}
```
