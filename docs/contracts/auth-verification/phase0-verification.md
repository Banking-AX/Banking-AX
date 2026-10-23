# VerificationPort Phase 0 contract

Owner: T1. Applies only immediately before B execution or initial C submission.

`issue(customerId, workItemId, action, requestVersion, registeredPhone)` returns an opaque `challengeId`, masked number, and expiry. It must verify customer ownership before sending. OTP plaintext is never returned, persisted, logged, or sent to an Agent.

`verify(customerId, challengeId, otp)` returns an opaque binding for exactly one `(customerId, workItemId, action, requestVersion)` tuple. `consume(bindingId, customerId, workItemId, action, requestVersion)` is one-use and rejects any tuple mismatch.

Errors: `OTP_INVALID`, `OTP_ATTEMPTS_EXCEEDED`, `VERIFICATION_BINDING_INVALID`. Execution/submission code must also recheck consent, current business rules, state, revision, and idempotency before consuming the binding.

Phase 0 uses an in-memory Caffeine TTL cache and a fake sender. It never sends SOLAPI messages. OTP duration, resend interval, and provider rate limits remain policy decisions; the current 180-second and five-attempt settings are test defaults, not a production policy decision.
