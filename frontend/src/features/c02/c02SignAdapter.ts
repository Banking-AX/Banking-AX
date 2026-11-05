import { ApiError } from '../../api/client'
import type { CodeIssue, CodeVerification, SignAdapter } from '../../api/sign'
import { c02Api } from '../../api/taskApi'

const requestId = (step: string, caseId: number) => `c02-${step}-${caseId}-${crypto.randomUUID()}`

/**
 * Server-backed C-02 adapter for SignFlow. The signature image never leaves the browser:
 * only "mock signature completed" goes with the consent record, and the Case revision returned
 * by each call is carried into the next one so issue/submit never send a stale revision.
 */
export function createC02SignAdapter(caseId: number, initialRevision: number, onSubmitted: (revision: number) => void): SignAdapter {
  let revision = initialRevision
  return {
    async submitSignature() {
      const consent = await c02Api.consent(caseId, { revision, requestId: requestId('consent', caseId), consented: true, mockSignatureCompleted: true })
      if (typeof consent.data?.revision === 'number') revision = consent.data.revision
    },
    async issueCode(): Promise<CodeIssue> {
      const response = await c02Api.issueVerification(caseId, { revision, requestId: requestId('issue', caseId) })
      const issue = response.data
      if (!issue?.challengeId) throw new ApiError('SMS_SEND_FAILED')
      return { challengeId: issue.challengeId, maskedPhone: issue.maskedPhone, expiresAt: issue.expiresAt }
    },
    async verifyCode(challengeId: string, otp: string): Promise<CodeVerification> {
      const response = await c02Api.verifyVerification({ requestId: requestId('verify', caseId), challengeId, otp })
      if (!response.data?.bindingId) throw new ApiError('OTP_INVALID')
      return { bindingId: response.data.bindingId }
    },
    async complete(verification: CodeVerification) {
      if (!verification.bindingId) throw new ApiError('VERIFICATION_BINDING_INVALID')
      const response = await c02Api.submit(caseId, { revision, requestId: requestId('submit', caseId), bindingId: verification.bindingId })
      onSubmitted(response.data?.revision ?? revision)
    },
  }
}
