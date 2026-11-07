import { describe, expect, it } from 'vitest'
import type { C02CaseIntake, C02Document, C02OcrDocument, C02OcrSnapshot, C02ReviewItem } from '../../api/taskApi'
import { buildOcrView, isOcrItem, ocrStatusLabel, staffCheckCount } from './c02OcrView'

const intake: C02CaseIntake = {
  decedentName: '김영준', decedentBirthDate: '1955-01-01', deathDate: '2020-11-01',
  heirs: [
    { personRef: 'a', name: '김민수', birthDate: '1997-04-12', relationship: '자녀', applicant: true, visitStatus: 'ATTENDING', delegationMethod: 'NONE' },
    { personRef: 'b', name: '김하나', birthDate: '1994-01-03', relationship: '형제', applicant: false, visitStatus: 'ABSENT', delegationMethod: 'DOCUMENT' },
  ],
}
const extracted = JSON.stringify({
  fields: {
    delegator_1_name: '김하나', delegator_2_name: '', delegator_1_address: '서울특별시 중구 테스트로 34', delegator_2_birth6: '년월일',
    '2_주소': '서울특별시 중구 테스트로 12', '2_본인과의 관계': '형제', '1_예금과목': '보통예금', '1_잔액': '600000',
  },
  standard: {
    depositorName: '김영준', agent: { name: '김민수' }, accounts: [{ accountNo: '99900000000001' }],
    delegators: [{ name: '김하나', birthDate: '19940103' }],
    reference: { depositorBirthDate: '19550101', agentBirthDate: '19970412', submittedDate: '20261106' },
  },
})
const document: C02OcrDocument = { documentFileId: 51, documentVersion: 1, method: 'CLOVA_TEMPLATE', status: 'SUCCEEDED', extracted, failureReason: null, manualVerificationRequired: true }
const ocrItems: C02ReviewItem[] = [
  { category: 'C02_POA_DOCUMENT_TYPE', result: 'TYPE_MATCHED', documentFileId: 51 },
  { category: 'C02_POA_depositorName', result: 'AUTO_MATCHED', documentFileId: 51 },
  { category: 'C02_POA_agent.name', result: 'AUTO_MATCHED', documentFileId: 51 },
  { category: 'C02_POA_delegators[].name', result: 'AUTO_MATCHED', documentFileId: 51 },
  { category: 'C02_POA_accounts[].accountNo', result: 'STAFF_REQUIRED', message: 'No Case account-number comparison source exists.', documentFileId: 51 },
]
const agentItems: C02ReviewItem[] = [
  { category: 'DELEGATION_DOCUMENT_PRIORITY', result: 'NEEDS_STAFF_REVIEW', documentFileId: 0 },
  { category: 'DOCUMENT_POLICY', result: 'NEEDS_STAFF_REVIEW', documentFileId: 0 },
  { category: 'POLICY_EVIDENCE', result: 'REFERENCE', documentFileId: 0 },
]
const row = (view: ReturnType<typeof buildOcrView>, label: string) => view?.rows.find(item => item.label === label)

describe('buildOcrView', () => {
  it('shows the application value, the OCR value and the stored result side by side', () => {
    const view = buildOcrView(document, intake, ocrItems)

    expect(view?.typeResult).toBe('TYPE_MATCHED')
    expect(row(view, '예금주')).toMatchObject({ caseValue: '김영준', ocrValue: '김영준', status: 'AUTO_MATCHED' })
    expect(row(view, '대리인')).toMatchObject({ caseValue: '김민수', ocrValue: '김민수', status: 'AUTO_MATCHED' })
    expect(row(view, '위임인')).toMatchObject({ caseValue: '김하나', ocrValue: '김하나', status: 'AUTO_MATCHED' })
  })

  it('keeps the account number as a staff check with its reason', () => {
    expect(row(buildOcrView(document, intake, ocrItems), '계좌번호')).toMatchObject({
      caseValue: '정본 정보 없음', ocrValue: '99900000000001', status: 'STAFF_REQUIRED', reason: '계좌번호를 대조할 은행 기준정보가 없어 직원 확인이 필요합니다.',
    })
  })

  it('never marks a value matched because it merely looks equal', () => {
    const view = buildOcrView(document, intake, ocrItems)

    expect(row(view, '예금주 생년월일')).toMatchObject({ caseValue: '1955-01-01', ocrValue: '1955-01-01', status: 'NOT_COMPARED' })
    expect(row(view, '대리인 생년월일')).toMatchObject({ caseValue: '1997-04-12', ocrValue: '1997-04-12', status: 'NOT_COMPARED' })
    expect(row(view, '위임인 생년월일 (김하나)')).toMatchObject({ caseValue: '1994-01-03', ocrValue: '1994-01-03', status: 'NOT_COMPARED' })
    for (const label of ['대리인 주소', '위임인 주소 (김하나)', '본인과의 관계', '예금과목', '잔액', '작성일']) expect(row(view, label)?.status).toBe('NOT_COMPARED')
  })

  it('shows stored birth date results and leaves older records as not compared', () => {
    const view = buildOcrView(document, intake, [...ocrItems,
      { category: 'C02_POA_depositorBirthDate', result: 'AUTO_MATCHED', documentFileId: 51 },
      { category: 'C02_POA_agent.birthDate', result: 'MISMATCH', documentFileId: 51 },
      { category: 'C02_POA_delegators[0].birthDate', result: 'STAFF_REQUIRED', documentFileId: 51 },
    ])

    expect(row(view, '예금주 생년월일')?.status).toBe('AUTO_MATCHED')
    expect(row(view, '대리인 생년월일')?.status).toBe('MISMATCH')
    expect(row(view, '위임인 생년월일 (김하나)')).toMatchObject({ status: 'STAFF_REQUIRED', reason: '자동 대조할 값이 부족합니다.' })
    expect(row(buildOcrView(document, intake, ocrItems), '예금주 생년월일')?.status).toBe('NOT_COMPARED')
  })

  it('reports no match when Spring stored no comparison, as for an unverifiable document', () => {
    const view = buildOcrView(document, intake, [{ category: 'C02_POA_DOCUMENT_TYPE', result: 'TYPE_UNVERIFIABLE', documentFileId: 51 }])

    expect(view?.typeResult).toBe('TYPE_UNVERIFIABLE')
    expect(view?.rows.every(item => item.status === 'NOT_COMPARED')).toBe(true)
  })

  it('shows a stored mismatch as a mismatch', () => {
    const view = buildOcrView(document, intake, [{ category: 'C02_POA_agent.name', result: 'MISMATCH', documentFileId: 51 }])

    expect(row(view, '대리인')?.status).toBe('MISMATCH')
  })

  it('ignores results that belong to another file', () => {
    const view = buildOcrView(document, intake, [{ category: 'C02_POA_depositorName', result: 'AUTO_MATCHED', documentFileId: 99 }])

    expect(row(view, '예금주')?.status).toBe('NOT_COMPARED')
  })

  it('creates no delegator row from a blank line of the form', () => {
    const labels = buildOcrView(document, intake, ocrItems)?.rows.map(item => item.label) ?? []

    expect(labels.filter(label => label.startsWith('위임인 생년월일'))).toEqual(['위임인 생년월일 (김하나)'])
    expect(buildOcrView(document, intake, ocrItems)?.rows.map(item => item.ocrValue)).not.toContain('년월일')
  })

  it('returns nothing for a document without a readable extraction', () => {
    expect(buildOcrView({ ...document, extracted: null }, intake, ocrItems)).toBeNull()
    expect(buildOcrView({ ...document, extracted: 'not json' }, intake, ocrItems)).toBeNull()
    expect(buildOcrView({ ...document, extracted: '{}' }, intake, ocrItems)).toBeNull()
  })
})

describe('staffCheckCount', () => {
  it('counts Agent and OCR checks once each, as stored for Case 8', () => {
    expect(staffCheckCount([...ocrItems, ...agentItems])).toBe(3)
  })

  it('counts a document type that could not be established', () => {
    expect(staffCheckCount([{ result: 'TYPE_UNVERIFIABLE' }, { result: 'OCR_UNSUPPORTED' }])).toBe(2)
  })

  it('does not count matches or reference material', () => {
    expect(staffCheckCount([{ result: 'AUTO_MATCHED' }, { result: 'TYPE_MATCHED' }, { result: 'REFERENCE' }])).toBe(0)
  })
})

describe('ocrStatusLabel', () => {
  const file = { documentFileId: 51 } as C02Document
  const snapshot = (status: string): C02OcrSnapshot => ({ caseId: 8, revision: 6, manualVerificationRequired: true, documents: [{ ...document, status }] })

  it('states each document status', () => {
    expect(ocrStatusLabel(file, snapshot('SUCCEEDED'))).toBe('OCR 내용 추출 완료 · 대조 결과 확인')
    expect(ocrStatusLabel(file, snapshot('NOT_APPLICABLE'))).toBe('자동 판독 대상 아님 · 원본 확인 필요')
    expect(ocrStatusLabel(file, snapshot('FAILED_NEEDS_STAFF'))).toBe('자동 인식 실패 · 직원 확인 필요')
  })

  it('separates a failed lookup from OCR that never ran', () => {
    expect(ocrStatusLabel(file, null)).toContain('확인 불가')
    expect(ocrStatusLabel({ documentFileId: 7 } as C02Document, snapshot('SUCCEEDED'))).toBe('자동 인식 미실행')
  })
})

describe('isOcrItem', () => {
  it('separates OCR results from the Agent list', () => {
    expect(ocrItems.every(isOcrItem)).toBe(true)
    expect(agentItems.some(isOcrItem)).toBe(false)
  })
})
