# Phase 0 core contract

Owner: T1. Consumers: T1 customer Agent and T2 task modules.

## Shared envelope

Every Spring Tool response uses `requestId`, `taskCode`, optional `workItemId`, `caseId`, `revision`, `data`, and nullable `error`. `error` contains a stable code, safe message, retryable flag, and trace ID. The caller must generate `requestId`; retries reuse it.

## TaskHandler

`start(StartTaskCommand)`, `resume(actorId, workItemId, expectedVersion)`, `hold(actorId, workItemId, expectedVersion, resumeStep)`, and `complete(actorId, workItemId, expectedVersion)` return `WorkItemView`.

The caller is the authenticated customer. The implementation enforces ownership, optimistic version equality, and at most one `ACTIVE` item per workflow. State names are the DDL values: `PLANNED`, `ACTIVE`, `ON_HOLD`, `DONE`, `HANDED_OFF`, `FAILED`, `CANCELED`.

Phase 0 adapter: `InMemoryTaskHandler`. It is replaced by a JPA transactional adapter without changing this contract.

## CasePort

`create`, `submit`, `revise`, `cancel`, `recordCheckResult`, and `get` use `ServiceCaseView`. Only `DEBT_ADJUSTMENT` and `INHERITANCE_DEPOSIT` create a Case. A submit moves `DRAFT` to `SUBMITTED` and sets review status to `PENDING`; it does not create a reservation or decide a staff result.

Revision and version mismatches return `VERSION_CONFLICT`. A worker result must carry `(caseId, revision)` so a stale run cannot overwrite a later revision. T2 invokes the worker-result boundary; Spring remains responsible for authorization and state persistence.

Phase 0 adapter: `InMemoryCasePort`.

## Tool Gateway

`POST /api/v1/tools/loan-inquiry` is the initial Tool. Required trusted gateway context is `X-Actor-Id`, `X-Actor-Role`, and `X-Tool-Gateway-Token`. The token must equal Spring's `APP_TOOL_GATEWAY_TOKEN`; an empty configured token rejects every request. The Spring service also rechecks that role is `CUSTOMER` and actor ID equals `customerId`.

Timeout and transport normalization are a FastAPI Gateway concern. Spring errors use the shared envelope. The development fake has no external financial side effect.

## T2 Fake ports

T1 tests consume interfaces only:

- `DocumentPort`: file/required-document ID, version, MIME, size, status.
- `register_check`: `(caseId, revision, taskCode, input)` to structured review items.
- `NotificationPort`: event ID and `dedupKey`.
- `VisitPort`: only accepts a notified, `REVIEW_PASSED` case with `visitRequired=true` for C-01.

These are fixtures until T2 provides the owned implementations.
