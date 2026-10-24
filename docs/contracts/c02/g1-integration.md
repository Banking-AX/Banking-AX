# C-02 G1 integration boundary

Owner: T2. This slice is an in-memory preparation boundary, not an inheritance-payment decision or a customer-authenticated submission API.

## Customer Agent to Spring prepare Tool

`POST /api/v1/c02/prepare` requires the existing internal Gateway headers: `X-Tool-Gateway-Token`, `X-Actor-Id`, and `X-Actor-Role: CUSTOMER`. Actor ID must equal `customerId`.

The body contains `requestId`, `workItemId`, `customerId`, `decedent`, `heirs`, and optional `documentStatuses`. It returns the common `ApiEnvelope` with `INHERITANCE_DEPOSIT`, a DRAFT Case for in-scope input, `visitRequired=true`, required document slots, and the Java `Submission` result. A pre-2008 death date or minor heir is returned as an out-of-scope result and does not create a Case. The same request ID with different input returns `DUPLICATE_REQUEST_CONFLICT`.

`documentStatuses` only represents the existing Fake DocumentPort slot state. Missing required slots keep `submittable=false`; this endpoint never creates a reservation or decides eligibility.

## Development-only C-02 submission boundary

The existing internal Gateway headers are also required for `POST /api/v1/c02/{caseId}/consent`, `/verification/issue`, `/verification/verify`, and `/submit`. `consent` records only consent and mock-signature completion. `issue`/`verify` use `C02_SUBMIT`, bound to customer, WorkItem, action, and Case revision. `submit` rechecks owner, revision, Java rules, latest in-memory document slots, consent/signature, and consumes the binding once before moving `DRAFT` to `SUBMITTED`.

The submit response includes the non-final employee Agent run status and review status. `SUCCEEDED` maps only to the Agent review axis; it is never a `PROCEED`, payment, reservation, or staff final decision. Repeating the same submit request ID returns the original result; changing customer, Case, or revision is a conflict.

This is explicitly an **InMemory development boundary**. Binding consumption and Case transition are not database-transactional: a synthetic Case-store failure after binding consumption leaves that binding consumed. JPA transaction/outbox work is required before production use.

## Staff precheck HTTP boundary

`POST /api/v1/staff-agent/check` requires `X-Staff-Agent-Token` equal to `STAFF_AGENT_GATEWAY_TOKEN`. It accepts `requestId`, `caseId`, `revision`, `taskCode`, and `caseSnapshot`, and returns the `register_check` response (`status`, `reviewStatus`, `reviewItems`, `error`). The composition explicitly registers C-01 and C-02 checks and uses a compiled LangGraph graph.

This FastAPI endpoint is an internal boundary only. Spring now calls it after a C-02 submit using a Spring-derived snapshot and rechecks Case revision before saving the Agent review axis. The FastAPI router still has an in-memory revision fixture; it is not a substitute for Spring CasePort persistence. It never creates `PROCEED`, a reservation, or a final financial result.

## Deferred connection

Production follow-up: replace the internal Gateway identity with a customer principal, use JPA transaction/outbox for submit and agent dispatch, and persist `agent_review_run`/`review_item` before treating the response as durable.
