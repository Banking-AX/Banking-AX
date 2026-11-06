import { ApiError } from '../client'
import { SMS_MAX_ATTEMPTS, SMS_RESEND_COOLDOWN_SEC, type CodeIssue, type SignAdapter, type SignDocKey } from '../sign'

// Mock only: mirrors the server SMS policy so the screen can be exercised without a backend.
const CODE_TTL_SEC = 180
const MAX_REQUESTS = 5
const MOCK_CODE = '274615'

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export function createMockSignAdapter(docKey: SignDocKey): SignAdapter {
  let sequence = 0
  let requestCount = 0
  let lastIssuedAt: number | null = null
  let current: { challengeId: string; expiresAt: number; wrong: number; used: boolean } | null = null

  return {
    // Shown only in development builds; production never exposes the mock code.
    devHint: import.meta.env.DEV ? `시연용 Mock 인증번호: ${MOCK_CODE}` : undefined,
    async submitSignature() {
      // The signature image is not sent anywhere in the mock.
      await delay(400)
    },
    async issueCode(): Promise<CodeIssue> {
      await delay(500)
      const now = Date.now()
      if (lastIssuedAt !== null && now - lastIssuedAt < SMS_RESEND_COOLDOWN_SEC * 1000) {
        throw new ApiError('SMS_RESEND_TOO_SOON', 429, 'Please wait before requesting another code.')
      }
      if (requestCount >= MAX_REQUESTS) throw new ApiError('SMS_REQUEST_LIMIT_EXCEEDED', 429, 'SMS request limit exceeded.')
      requestCount += 1
      lastIssuedAt = now
      sequence += 1
      current = { challengeId: `mock-${sequence}`, expiresAt: now + CODE_TTL_SEC * 1000, wrong: 0, used: false }
      return { challengeId: current.challengeId, maskedPhone: '010-****-5821', expiresAt: new Date(current.expiresAt).toISOString() }
    },
    async verifyCode(challengeId, otp) {
      await delay(400)
      if (!current || current.challengeId !== challengeId) throw new ApiError('OTP_INVALID', 400, 'The verification code is invalid.')
      if (Date.now() >= current.expiresAt) throw new ApiError('OTP_EXPIRED', 400, 'The verification code has expired.')
      if (current.used) throw new ApiError('OTP_ALREADY_USED', 409, 'The verification code has already been used.')
      if (current.wrong >= SMS_MAX_ATTEMPTS) throw new ApiError('OTP_ATTEMPTS_EXCEEDED', 400, 'OTP attempts exceeded.')
      if (otp !== MOCK_CODE) {
        current.wrong += 1
        throw new ApiError('OTP_INVALID', 400, 'The verification code is invalid.')
      }
      current.used = true
      return docKey === 'b01' ? {} : { bindingId: 'mock-binding-1' }
    },
    async complete() {
      // Placeholder for the real B execution or C submission.
      await delay(1300)
    },
  }
}
