# A-02 loan inquiry Tool contract

Owner: T1. Caller: registered customer task only.

`POST /api/v1/tools/loan-inquiry`

Headers:

- `X-Actor-Id`: authenticated customer ID
- `X-Actor-Role`: `CUSTOMER`
- `X-Tool-Gateway-Token`: value shared through `SPRING_TOOL_GATEWAY_TOKEN` / `APP_TOOL_GATEWAY_TOKEN`, never committed

Request body:

```json
{"requestId":"req-a02-001","workItemId":1001,"customerId":1,"loanId":101}
```

Response data contains only loans owned by the customer and includes masked loan number, balance, rate, overdue days, acceleration state, and repayment order. `loanId` is optional to list owned loans.

`FORBIDDEN` is returned for role/ownership mismatch; `INVALID_REQUEST` for a missing request ID. The Phase 0 data source is `FakeLoanQueryPort`; no database financial record is changed.
