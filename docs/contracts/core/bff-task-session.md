# Session BFF task contract — T2 integration

Status: **IMPLEMENTED for T2 task routes.** It reuses the existing JWT
authentication and is not a second authentication implementation.

> Update 2026-11-01: the T2 task BFF is now implemented in Spring and reuses
> the existing JWT principal. The historical route table below describes the
> original handoff; the effective browser routes are
> `POST /api/v1/customer/a01/transaction-search`,
> `/api/v1/customer/b02/**`, `/api/v1/customer/c02/**`,
> `/api/v1/staff/c02/**`, `/api/v1/customer/notifications/**`, and
> `/api/v1/customer/reservations/**`. They derive identity from the JWT and
> supply the internal Tool Gateway credential only server-side. Set
> `VITE_BFF_TASKS_ENABLED=true` only with `APP_AUTH_ENABLED=true` and a
> server-only `APP_TOOL_GATEWAY_TOKEN`.

## Security boundary

The browser sends same-origin cookie requests only. It never sends
`X-Actor-Id`, `X-Actor-Role`, `X-Tool-Gateway-Token`, a trusted `customerId`,
or a staff identity. The BFF must authenticate the session, derive the actor,
reject conflicting client identity fields, and add the three internal headers
only on its trusted hop to Spring/FastAPI.

For a CUSTOMER session, `customerId` supplied to the internal request must be
the session customer. For STAFF routes, the BFF must require the shared JWT
claim/authority `EMPLOYEE` (the database/API label remains `STAFF`) and supply
the session staff ID. The BFF must not accept a browser request that chooses a
different customer or staff member.

## Browser DTO to internal DTO mapping

| Browser endpoint and body | BFF internal destination | Required BFF enrichment |
| --- | --- | --- |
| `POST /api/v1/customer-agent/execute`: `taskCode`, `requestId`, `workItemId`, `accountId`, query filters | FastAPI customer-agent execute | Add `customerId` from CUSTOMER session. FastAPI then calls Spring Tool Gateway with trusted headers. |
| `POST /api/v1/b02/prepare`: `requestId`, `account`, `waive`, `taxed` | Spring B-02 prepare | Add `customer=session.customerId`. |
| `POST /api/v1/b02/{accountId}/otp`: `requestId`, `version`, `consent`, `signed`, `phone`, `workItemId` | Spring B-02 OTP | Add `customer=session.customerId`; preserve path `accountId` and body `workItemId` as distinct values. |
| `POST /api/v1/b02/otp/verify`: `requestId`, `challenge`, `otp` | Spring B-02 verify | Add `customer=session.customerId`. |
| `POST /api/v1/b02/{accountId}/execute`: `requestId`, `version`, `waive`, `taxed`, `binding`, `workItemId` | Spring B-02 execute | Add `customer=session.customerId`; do not substitute account ID for WorkItem ID. |
| `POST /api/v1/c02/prepare`: `requestId`, `workItemId`, `registeredPhone`, `decedent`, `heirs`, `documentStatuses` | Spring C-02 prepare | Add `customerId=session.customerId`. |
| C-02 consent/OTP/submit/upload/document/read/review customer routes | corresponding Spring C-02 route | Add customer ID in the body/query/form field expected by the existing controller. Verify path Case/document ownership through Spring. |
| `GET /api/v1/c02/{caseId}/staff-case`, `POST /staff-decision` | Spring C-02 staff routes | Require STAFF session; inject staff actor headers. Customer session must be rejected. |
| notification and reservation routes | Spring notification/reservation routes | Derive CUSTOMER actor from session. Spring remains the owner/case/status authority. |

The React API user role remains `STAFF`; it is returned by `/api/v1/auth/me`
from the database role. The server-side JWT claim for the same employee is
`EMPLOYEE`, which is the role required by `/api/v1/staff/**`.

## Identifier rules

- `workItemId` is created by the workflow start path and is required by A-01,
  B-02 and C-02; it must not be inferred from an account or Case ID.
- `accountId` is the owned financial account target. Spring validates ownership.
- `caseId` is returned only after C-02 prepare and is customer-owned through
  Spring `service_case` checks.
- `revision` is returned from the latest Case response. Submit, document,
  precheck and staff decision requests must use that revision; stale revisions
  are rejected by Spring.
- `requestId` is client-generated idempotency input. BFF forwards it unchanged.

## Required responses

The BFF preserves the common `ApiEnvelope<T>` shape:
`requestId`, `taskCode`, `workItemId`, `caseId`, `revision`, `data`, `error`.
It must preserve Spring error codes such as `FORBIDDEN`, `VERSION_CONFLICT`,
`DUPLICATE_REQUEST_CONFLICT`, `RESERVATION_NOT_ALLOWED`, and verification
errors rather than turning them into a successful UI result.

## Activation

Only after the above routes are backed by a real session BFF may the frontend
deployment set `VITE_BFF_TASKS_ENABLED=true`. Development Vite proxying
directly to FastAPI/Spring is not a replacement for this boundary.

## Authentication and browser transport requirements

The merged JWT PR provides `POST /api/v1/auth/login`, `POST
/api/v1/auth/refresh`, `POST /api/v1/auth/logout`, and `GET
/api/v1/auth/me`, including an in-memory access token and an HttpOnly refresh
cookie. It does not provide the task BFF described in this document. The
frontend must keep task calls disabled until that server-side bridge exists.

- A successful login must establish a server-verified session using an
  `HttpOnly` cookie. `Secure` must be enabled in HTTPS deployments; the exact
  `SameSite` value must match the final same-site or cross-site topology.
- State-changing cookie-authenticated BFF routes need a CSRF strategy. Cookie
  `SameSite` alone is not an authorization or CSRF replacement.
- CORS must allow only the deployed React origin and credentials when a
  separate origin is unavoidable. Production should prefer the same origin
  used by `apiFetch(..., { credentials: 'include' })`.
- The BFF, not the browser, owns the Spring gateway credential and any
  FastAPI-to-Spring service credential. It must remove client-supplied copies
  of those headers before forwarding.
- The JWT Security configuration deliberately grants existing `/api/v1/tools/**`,
  `/api/v1/b02/**`, and `/api/v1/c02/**` routes only to `ROLE_TOOL`. A CUSTOMER
  Bearer token must not be treated as an internal Tool credential; the BFF
  must be a separate trusted server-side hop.

## WorkItem and chat handoff gap

`workItemId` must be created before the A-01, B-02, or C-02 task clients can
run. The internal Spring workflow-start endpoint is deliberately token-gated.
The accessible FastAPI natural-language start route currently auto-starts only
`LOAN_INQUIRY` (A-02), while the existing React chat client remains a mock.
Therefore the common owner must either extend that natural-language start
route to return the customer-confirmed A-01/B-02/C-02 WorkItem, or provide a
session-BFF route that performs the approved start flow server-side. The
browser must never call `/api/v1/tools/workflows/start` directly.

## Remaining common-chat handoff

The BFF deliberately does not create WorkItems. When the common Chat service
is merged, its trusted server hop must create the confirmed A-01/B-02/C-02
WorkItem and return its `workItemId` to the task page. A browser must never
choose a customer, staff actor, or internal Tool Gateway credential.
