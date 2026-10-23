# C-01 Phase 0 contract boundary

Owner: T1. C-01 complete intake, document upload, and staff result UI are outside this phase.

The CasePort input identifies `DEBT_ADJUSTMENT`, work item, selected loan, applicant fields, and current application revision. Submit requires a valid C01 verification binding plus consent and rules revalidation when the real API is added. Spring moves the Case to `SUBMITTED` and requests an asynchronous T2 `register_check` using `(caseId, revision, taskCode, input)`.

The T2 check result may set only the Agent review axis (`PENDING`, `RUNNING`, `WAITING_CUSTOMER`, `READY_FOR_STAFF`, `FAILED_NEEDS_STAFF`) through the Spring boundary. It cannot decide `PROCEED`, create a reservation, or write final financial results.

Phase 0 Fake result:

```json
{"caseId":2001,"revision":0,"status":"READY_FOR_STAFF","reviewItems":[{"source":"SPRING_RULE","result":"STAFF_CONFIRMATION_REQUIRED","evidence":[]}]} 
```
