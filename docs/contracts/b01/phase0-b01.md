# B-01 Phase 0 contract boundary

Owner: T1. B-01 rules and transactions are not implemented in Phase 0.

The future execute request must include `workItemId`, `requestId`, current request version, consent version, and VerificationPort binding. Spring must re-read the loan and enforce B-R1 through B-R5 before changing `loan.repayment_order`; a retry must not change it twice. Result data belongs in `task_result` with before/after values and rule version.

Fake behavior for Phase 0: a deterministic `ELIGIBLE`/`NOT_ELIGIBLE` fixture only. No loan update and no notification are emitted.
