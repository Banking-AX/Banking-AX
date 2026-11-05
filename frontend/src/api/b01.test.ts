import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRealB01Adapter, mapB01Eligibility, type B01ImageCheck, type ServerEligibility } from './b01'
import { setAccessToken } from './client'

const envelope = (data: unknown) => JSON.stringify({
  requestId: 'req', taskCode: 'REPAYMENT_ORDER_CHANGE', workItemId: 100,
  caseId: null, revision: null, data, error: null,
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
})

describe('B-01 Spring BFF adapter', () => {
  it('maps the server loan fields and sends the JWT only through the shared client', async () => {
    setAccessToken('customer-token')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer customer-token')
      return new Response(envelope([{
        loanId: 501, maskedAccountNumber: '210-***-000001', loanName: '가상 대출', balance: 26500000,
        overdueDays: 30, accelerationDeclared: true, accelerationDate: '2026-08-31', currentRepaymentOrder: 'INTEREST_FIRST',
      }]), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(createRealB01Adapter('100').listLoans()).resolves.toEqual([{
      loanId: '501', loanNoMasked: '210-***-000001', loanName: '가상 대출', balance: 26500000,
      overdueDays: 30, accelerationDeclared: true, accelerationDate: '2026-08-31', repaymentOrder: 'INTEREST_FIRST',
    }])
  })

  it('requires loan-rule, OCR and image matching separately from automatic processing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(envelope({
      ruleCode: 'ELIGIBLE', loanRuleEligible: true, thirdPartyProvider: false,
      additionalConfirmationRequired: false,
      ocr: { matched: true, mismatchCodes: [], documentFileId: 77 },
      image: { matched: true, status: 'MATCHED', mismatchCodes: [] },
      verification: { signaturesVerified: false, authenticityVerified: false, manualVerificationRequired: true },
      automaticProcessingAllowed: false,
    }), { status: 200 })))

    const result = await createRealB01Adapter('100').checkEligibility('501', 'PRINCIPAL_FIRST')
    expect(result).toMatchObject({ loanRuleEligible: true, eligible: true, ocrMatched: true, image: { matched: true }, automaticProcessingAllowed: false, manualVerificationRequired: true })
  })

  it.each([
    ['missing image result', undefined],
    ['uncertain analysis', { matched: false, status: 'UNCERTAIN', mismatchCodes: ['IMAGE_ANALYSIS_UNCERTAIN'] }],
    ['unchecked boxes', { matched: false, status: 'REQUIRES_RESUBMISSION', mismatchCodes: ['CHECKBOX_NOT_SELECTED'] }],
    ['multiple boxes', { matched: false, status: 'REQUIRES_RESUBMISSION', mismatchCodes: ['MULTIPLE_CHECKBOXES'] }],
    ['requested-order mismatch', { matched: false, status: 'REQUIRES_RESUBMISSION', mismatchCodes: ['REQUESTED_ORDER_MISMATCH'] }],
    ['missing signature trace', { matched: false, status: 'REQUIRES_RESUBMISSION', mismatchCodes: ['DEBTOR_SIGNATURE_TRACE_NOT_FOUND'] }],
    ['analysis outage', { matched: false, status: 'UNAVAILABLE', mismatchCodes: ['IMAGE_ANALYSIS_UNAVAILABLE'] }],
  ] as const)('fails closed for %s', (_name, image) => {
    const server: ServerEligibility = {
      ruleCode: 'ELIGIBLE', loanRuleEligible: true, thirdPartyProvider: false,
      additionalConfirmationRequired: false,
      ocr: { matched: true, mismatchCodes: [], documentFileId: 77 },
      image: image as B01ImageCheck | undefined,
      verification: { signaturesVerified: false, authenticityVerified: false, manualVerificationRequired: true },
      automaticProcessingAllowed: false,
    }
    expect(mapB01Eligibility(server).eligible).toBe(false)
  })

  it('applies the same image gate when restoring eligibility from context', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(envelope({
      workItemId: 100, workItemVersion: 3, workItemStatus: 'ACTIVE', resumeStep: null, selectedLoanId: 501,
      requestedRepaymentOrder: 'PRINCIPAL_FIRST', applicationDocument: null,
      lastEligibility: {
        ruleCode: 'ELIGIBLE', loanRuleEligible: true, thirdPartyProvider: false,
        additionalConfirmationRequired: false,
        ocr: { matched: true, mismatchCodes: [], documentFileId: 77 },
        image: { matched: false, status: 'UNCERTAIN', mismatchCodes: ['IMAGE_ANALYSIS_UNCERTAIN'] },
        verification: { signaturesVerified: false, authenticityVerified: false, manualVerificationRequired: true },
        automaticProcessingAllowed: false,
      },
      consentAgreed: false, mockSignatureCompleted: false, smsVerified: false,
      currentStep: 'ELIGIBILITY_REVIEW', readyToExecute: false,
    }), { status: 200 })))

    const restored = await createRealB01Adapter('100').getContext()
    expect(restored.lastEligibility).toMatchObject({ eligible: false, ocrMatched: true, image: { matched: false, status: 'UNCERTAIN' } })
  })

  it('reuses the same requestId when the same selection is retried in one page session', async () => {
    const bodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(String(init?.body))
      return new Response(envelope({
        workItemId: 100, workItemVersion: 3, workItemStatus: 'ACTIVE', resumeStep: null, selectedLoanId: 501,
        requestedRepaymentOrder: 'PRINCIPAL_FIRST', applicationDocument: null, lastEligibility: null,
        consentAgreed: false, mockSignatureCompleted: false, smsVerified: false,
        currentStep: 'APPLICATION_UPLOAD', readyToExecute: false,
      }), { status: 200 })
    }))
    const adapter = createRealB01Adapter('100')

    await adapter.saveSelection('501', 'PRINCIPAL_FIRST')
    await adapter.saveSelection('501', 'PRINCIPAL_FIRST')

    expect(JSON.parse(bodies[0]).requestId).toBe(JSON.parse(bodies[1]).requestId)
  })

  it('uploads multipart without overriding the browser boundary content type', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.body).toBeInstanceOf(FormData)
      expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
      return new Response(envelope({
        documentFileId: 77, version: 1, documentTypeCode: 'B01_REPAYMENT_ORDER_APPLICATION',
        detectedType: 'B01_REPAYMENT_ORDER_APPLICATION', method: 'MOCK', status: 'SUCCEEDED',
        fields: { loans: [], requestedRepaymentOrder: 'PRINCIPAL_FIRST' },
        signatureChecks: { debtorSignatureField: 'NOT_VERIFIED', thirdPartySignatureField: 'NOT_VERIFIED' },
        authenticityVerified: false, manualVerificationRequired: true,
      }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['fixture'], 'application.pdf', { type: 'application/pdf' })
    await expect(createRealB01Adapter('100').uploadApplication(file)).resolves.toMatchObject({ documentFileId: 77, method: 'MOCK' })
  })

  it('reports DONE only from the server execute result', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => new Response(envelope({
      status: 'DONE', processedAt: '2026-11-05T09:00:00Z', consentAt: '2026-11-05T08:59:00Z',
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(createRealB01Adapter('100').finalize()).resolves.toEqual({
      status: 'DONE', processedAt: '2026-11-05T09:00:00Z', consentAt: '2026-11-05T08:59:00Z',
    })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/b01/work-items/100/execute')
  })

  it('sends the context version to the common WorkItem resume API', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe('/api/v1/customer/work-items/100/resume')
      expect(init?.method).toBe('POST')
      expect(JSON.parse(String(init?.body))).toEqual({ expectedVersion: 7 })
      return new Response(envelope({
        workItemId: 100, workflowId: 10, taskCode: 'REPAYMENT_ORDER_CHANGE',
        status: 'ACTIVE', version: 8, resumeStep: 'CONSENT',
      }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(createRealB01Adapter('100').resume(7)).resolves.toMatchObject({
      workItemId: 100, status: 'ACTIVE', version: 8, resumeStep: 'CONSENT',
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
