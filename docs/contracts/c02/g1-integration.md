# C-02 G1 integration boundary

Owner: T2. This slice is an in-memory preparation boundary, not an inheritance-payment decision or a customer-authenticated submission API.

## Customer Agent to Spring prepare Tool

`POST /api/v1/c02/prepare` requires the existing internal Gateway headers: `X-Tool-Gateway-Token`, `X-Actor-Id`, and `X-Actor-Role: CUSTOMER`. Actor ID must equal `customerId`.

The body contains `requestId`, `workItemId`, `customerId`, `decedent`, `heirs`, and optional `documentStatuses`. It returns the common `ApiEnvelope` with `INHERITANCE_DEPOSIT`, a DRAFT Case for in-scope input, `visitRequired=true`, required document slots, and the Java `Submission` result. A pre-2008 death date or minor heir is returned as an out-of-scope result and does not create a Case. The same request ID with different input returns `DUPLICATE_REQUEST_CONFLICT`.

`documentStatuses` only represents the existing Fake DocumentPort slot state. Missing required slots keep `submittable=false`; this endpoint never submits a Case, consumes a `C02_SUBMIT` verification binding, creates a reservation, or decides eligibility.

## Staff precheck HTTP boundary

`POST /api/v1/staff-agent/check` requires `X-Staff-Agent-Token` equal to `STAFF_AGENT_GATEWAY_TOKEN`. It accepts `requestId`, `caseId`, `revision`, `taskCode`, and `caseSnapshot`, and returns the `register_check` response (`status`, `reviewStatus`, `reviewItems`, `error`). The composition explicitly registers C-01 and C-02 checks and uses a compiled LangGraph graph.

This FastAPI endpoint is an internal boundary only. Until Spring owns C-02 submit and invokes it after re-reading a `SUBMITTED` Case, the endpoint's local revision fixture is not a substitute for Spring CasePort persistence. It never creates `PROCEED`, a reservation, or a final financial result.

## Deferred connection

The future Spring submit flow must recheck customer principal, consent, a `C02_SUBMIT` VerificationPort binding, document slots, and current revision; only then may it call the staff endpoint with a Spring-derived snapshot and save the response after a second revision check.
