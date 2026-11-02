export type SignDocKey = 'b01' | 'b02' | 'b02base' | 'c01' | 'c02'

export const SIGN_DOC_KEYS: readonly SignDocKey[] = ['b01', 'b02', 'b02base', 'c01', 'c02']

export function isSignDocKey(value: string | undefined): value is SignDocKey {
  return value !== undefined && (SIGN_DOC_KEYS as readonly string[]).includes(value)
}

// Values that must stay in line with the server SMS policy (app.sms.max-attempts, app.sms.resend-seconds).
export const SMS_MAX_ATTEMPTS = 5
export const SMS_RESEND_COOLDOWN_SEC = 60

/** path is an SVG path "d" string in the 600x220 signature viewBox. */
export type SignatureSubmission = { path: string; pointCount: number; documentCount: number; toPngBlob(): Promise<Blob> }

/** expiresAt is an ISO timestamp. */
export type CodeIssue = { challengeId: string; maskedPhone: string; expiresAt: string }

/** B-02 and C-02 return a bindingId; B-01 does not. */
export type CodeVerification = { bindingId?: string }

/**
 * One adapter per task contract. Every method rejects with ApiError.
 * Codes: OTP_INVALID, OTP_EXPIRED, OTP_ALREADY_USED, OTP_ATTEMPTS_EXCEEDED, SMS_RESEND_TOO_SOON,
 * SMS_REQUEST_LIMIT_EXCEEDED, SMS_ALREADY_VERIFIED, REGISTERED_PHONE_NOT_AVAILABLE, CONSENT_REQUIRED,
 * MOCK_SIGNATURE_REQUIRED, INVALID_SIGNATURE_FILE, CONSENT_OR_SIGNATURE_REQUIRED, VERIFICATION_BINDING_INVALID
 */
export interface SignAdapter {
  // B-01: consent, then mock-signature (PNG upload). B-02: keep consent/signed for the otp request. C-02: consent.
  submitSignature(s: SignatureSubmission): Promise<void>
  // B-01: sms/requests. B-02: {accountId}/otp. C-02: {caseId}/verification/issue.
  issueCode(): Promise<CodeIssue>
  // B-01: sms/verifications. B-02: otp/verify (bindingId). C-02: verification/verify (bindingId).
  verifyCode(challengeId: string, otp: string): Promise<CodeVerification>
  // B-01: no execute API yet. B-02: {accountId}/execute(binding). C-02: {caseId}/submit(bindingId).
  complete(v: CodeVerification): Promise<void>
  devHint?: string
}
