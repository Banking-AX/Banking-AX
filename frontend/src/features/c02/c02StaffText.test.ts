import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { C02DocumentReviewRow, C02ReviewItem, C02StaffCase } from '../../api/taskApi'
import { C02OcrResults } from './C02OcrResults'
import { C02AiDraftCard } from './C02AiReview'
import { STAFF_INPUT_MARK, agentItemView, customerNoticeDraft, decisionGuide, noticeDocumentLines, runLabel, staffSummaryLines, statusLabel } from './c02StaffText'

const ocr: C02ReviewItem[] = [
  { category: 'C02_POA_DOCUMENT_TYPE', result: 'TYPE_MATCHED', source: 'SPRING_RULE', documentFileId: 51 },
  { category: 'C02_POA_depositorName', result: 'AUTO_MATCHED', source: 'SPRING_RULE', documentFileId: 51 },
  { category: 'C02_POA_agent.name', result: 'AUTO_MATCHED', source: 'SPRING_RULE', documentFileId: 51 },
  { category: 'C02_POA_delegators[].name', result: 'AUTO_MATCHED', source: 'SPRING_RULE', documentFileId: 51 },
  { category: 'C02_POA_accounts[].accountNo', result: 'STAFF_REQUIRED', source: 'SPRING_RULE', documentFileId: 51 },
]
const agent: C02ReviewItem[] = [
  { category: 'DELEGATION_DOCUMENT_PRIORITY', result: 'NEEDS_STAFF_REVIEW', source: 'AGENT_SEMANTIC', message: 'An absent heir selected written delegation.' },
  { category: 'DOCUMENT_POLICY', result: 'NEEDS_STAFF_REVIEW', source: 'PRE_SUBMISSION', message: '일반 증명서 원문은 OCR 미적용' },
  { category: 'POLICY_EVIDENCE', result: 'REFERENCE', source: 'AGENT_SEMANTIC', message: '업무 근거: 상속예금 해지·지급 FAQ – 공통 유의사항' },
]
function staffCase(overrides: Partial<C02StaffCase> = {}, reviewItems: C02ReviewItem[] = [...agent, ...ocr], agentRunStatus = 'SUCCEEDED'): C02StaffCase {
  return {
    caseView: { caseId: 8, workItemId: 35, revision: 6, caseStatus: 'SUBMITTED', reviewStatus: 'READY_FOR_STAFF', visitRequired: true },
    requiredDocuments: [{ documentTypeCode: 'ABSENT_HEIR_POWER_OF_ATTORNEY', requirement: 'REQUIRED', personRef: 'b', note: '' }],
    documents: [],
    precheck: { caseId: 8, workItemId: 35, revision: 6, agentRunStatus, reviewStatus: 'READY_FOR_STAFF', reviewItems, error: null, finalStaffDecision: false },
    intake: { decedentName: '김영준', decedentBirthDate: '1955-01-01', deathDate: '2020-11-01', heirs: [
      { personRef: 'a', name: '김민수', relationship: '자녀', applicant: true, visitStatus: 'ATTENDING', delegationMethod: 'NONE' },
      { personRef: 'b', name: '김하나', relationship: '형제', applicant: false, visitStatus: 'ABSENT', delegationMethod: 'DOCUMENT' },
    ] },
    summary: { heirCount: 2, absentHeirCount: 1, requiredUploadCount: 6, uploadedRequiredCount: 6, missingRequiredCount: 0, visitOnlyCount: 2, autoMatchedCount: 3, mismatchCount: 0, precheckNotRun: false, manualVerificationRequired: true },
    finalDecision: null,
    ...overrides,
  }
}
const body = (data: C02StaffCase, title: string) => staffSummaryLines(data).find(line => line.title === title)?.body

const checked = (documentTypeCode: string, requirement: string, file: number | null, result: 'CONFIRMED' | 'SUPPLEMENT_REQUIRED' | null, reason: string | null, personName: string | null = null): C02DocumentReviewRow => ({
  requiredDocumentId: 1, documentTypeCode, requirement, personRef: null, personName, documentFileId: file, fileVersion: file && 1, fileName: null, uploadedAt: null, ocrMethod: null, ocrStatus: null,
  review: result && { eventId: 1, result, reason, staffId: 1, staffName: null, reviewedAt: '2026-11-08T00:00:00Z' },
})
const LINES = ['- 상속인 신분증 (김민수): 제출 서류 양식이 달라요', '- 기본증명서: 아직 제출되지 않았어요']

describe('customer notice draft', () => {
  it('lists only missing required files and files staff marked for supplement', () => {
    const lines = noticeDocumentLines([
      checked('HEIR_ID_CARD', 'REQUIRED', 11, 'SUPPLEMENT_REQUIRED', '제출 서류 양식이 달라요', '김민수'),
      checked('DECEDENT_BASIC_CERTIFICATE', 'REQUIRED', null, null, null),
      checked('DECEDENT_FAMILY_RELATION_DETAIL', 'REQUIRED', 12, 'CONFIRMED', null),
      checked('DECEDENT_BASIC_CERTIFICATE', 'REQUIRED', 13, null, null),
      checked('DECEDENT_BASIC_CERTIFICATE', 'OPTIONAL', null, null, null),
    ])
    expect(lines).toHaveLength(2)
    expect(lines[0]).toContain('(김민수): 제출 서류 양식이 달라요')
    expect(lines[1]).toContain('아직 제출되지 않았어요')
  })

  it('names the documents and their reasons for an insufficient result', () => {
    const draft = customerNoticeDraft('DOCUMENT_INSUFFICIENT', LINES)
    for (const line of LINES) expect(draft).toContain(line)
    expect(draft).toContain('새로 신청')
    expect(draft).not.toContain(STAFF_INPUT_MARK)
  })

  it('leaves what only staff know to staff instead of guessing', () => {
    expect(customerNoticeDraft('DOCUMENT_INSUFFICIENT', [])).toContain(STAFF_INPUT_MARK)
    expect(customerNoticeDraft('REQUIREMENT_FAILED', LINES)).toContain(STAFF_INPUT_MARK)
  })

  it('tells a proceeding customer what to bring, and adds no list when nothing is open', () => {
    expect(customerNoticeDraft('PROCEED', LINES)).toContain(LINES[0])
    expect(customerNoticeDraft('PROCEED', [])).not.toContain('다시 준비')
  })

  it('has no draft of its own before a result is chosen unless documents are open', () => {
    expect(customerNoticeDraft('', [])).toBeNull()
    expect(customerNoticeDraft('', LINES)).toContain(LINES[1])
  })

  it('shows the composed draft, and offers to apply it only once a result is chosen', () => {
    const card = (outcomeLabel: string | null) => renderToStaticMarkup(createElement(C02AiDraftCard, { caseId: 1, revision: 1, canGenerate: false, outcomeLabel, composedDraft: LINES[0], onApply: () => undefined }))
    expect(card(null)).toContain(LINES[0])
    expect(card(null)).not.toContain('안내 문구란에 넣고')
    expect(card('서류 부족')).toContain('안내 문구란에 넣고')
  })
})

describe('statusLabel', () => {
  it('turns stored codes into business wording', () => {
    expect(statusLabel('READY_FOR_STAFF')).toBe('사전점검 완료 · 직원 확인 대기')
    expect(statusLabel('PENDING')).toBe('사전점검 대기')
    expect(runLabel('SUCCEEDED')).toBe('실행 완료')
  })

  it('never hides a code it does not know', () => {
    expect(statusLabel('NEW_CODE')).toBe('NEW_CODE')
    expect(statusLabel(null)).toBe('미확인')
  })
})

describe('staffSummaryLines', () => {
  it('states the Case 8 result from the response', () => {
    const data = staffCase()

    expect(body(data, '서류 제출 현황')).toBe('필수서류 6건이 모두 업로드되었습니다. 서류 내용과 원본의 적정성은 직원 확인이 필요합니다.')
    expect(body(data, '위임장 자동 대조')).toBe('위임장의 예금주·대리인·위임인 성명이 신청정보와 일치합니다. (3건)')
    expect(body(data, '추가 확인 필요')).toBe('계좌번호, 위임인의 서명·인감 및 위임 의사는 직원이 직접 확인해야 합니다.')
    expect(body(data, '우선 확인할 서류')).toBe('미방문 상속인 김하나의 위임장, 신분증 사본 및 인감증명서를 우선 확인해 주세요.')
  })

  it('explains the two counts without adding them together', () => {
    expect(body(staffCase(), '건수 기준')).toBe('직원 확인 3건은 자동 대조에서 넘어온 1건과 사전점검 안내 2건입니다. 자동 대조 일치 3건은 여기에 포함되지 않습니다.')
  })

  it('uses other people and counts from the data', () => {
    const data = staffCase({ intake: { decedentName: 'x', decedentBirthDate: null, deathDate: null, heirs: [
      { personRef: 'c', name: '박서준', relationship: '자녀', applicant: false, visitStatus: 'ABSENT', delegationMethod: 'DOCUMENT' },
    ] } }, [...ocr.slice(0, 2), { category: 'C02_POA_depositorBirthDate', result: 'AUTO_MATCHED' }])

    expect(body(data, '위임장 자동 대조')).toBe('위임장의 예금주 성명, 예금주 생년월일이 신청정보와 일치합니다. (2건)')
    expect(body(data, '우선 확인할 서류')).toContain('박서준')
    expect(body(data, '추가 확인 필요')).not.toContain('계좌번호')
  })

  it('shows no success sentence without a match', () => {
    const unverifiable = staffCase({}, [{ category: 'C02_POA_DOCUMENT_TYPE', result: 'TYPE_UNVERIFIABLE' }])
    const noResult = staffCase({}, agent)

    expect(body(unverifiable, '위임장 자동 대조')).toContain('자동으로 확인하지 못해')
    expect(body(noResult, '위임장 자동 대조')).toContain('결과가 없습니다')
    for (const data of [unverifiable, noResult]) expect(body(data, '위임장 자동 대조')).not.toContain('일치')
  })

  it('reports a mismatch beside the matches', () => {
    const data = staffCase({}, [...ocr, { category: 'C02_POA_agent.birthDate', result: 'MISMATCH' }])

    expect(body(data, '불일치 확인')).toBe('위임장의 대리인 생년월일이 신청정보와 다릅니다. 원본과 신청정보를 대조해 주세요. (1건)')
  })

  it('reports missing documents instead of a complete upload', () => {
    const data = staffCase({ summary: { ...staffCase().summary, uploadedRequiredCount: 4, missingRequiredCount: 2 } })

    expect(body(data, '서류 제출 현황')).toBe('필수서류 6건 중 2건이 제출되지 않았습니다.')
  })

  it('omits the delegation lines when nobody delegated', () => {
    const data = staffCase({ requiredDocuments: [], intake: { decedentName: 'x', decedentBirthDate: null, deathDate: null, heirs: [] } }, [])

    expect(body(data, '위임장 자동 대조')).toBeUndefined()
    expect(body(data, '우선 확인할 서류')).toBeUndefined()
    expect(body(data, '추가 확인 필요')).toBe('서류 원본과 상속관계는 직원이 직접 확인해야 합니다.')
  })

  it('says when the precheck has not run', () => {
    expect(body(staffCase({}, [], 'NOT_RUN'), '자동 사전점검')).toContain('아직 실행되지')
  })
})

describe('agentItemView', () => {
  it('replaces English Agent text and keeps the source apart', () => {
    expect(agentItemView(agent[0])).toEqual({ title: '위임서류 우선 확인', body: '방문하지 않는 상속인이 서면 위임을 선택했습니다. 위임장, 신분증 사본 및 인감증명서를 먼저 확인해 주세요.', source: '직원 Agent 안내' })
    expect(agentItemView(agent[1]).source).toBe('서류 정책 안내')
  })

  it('shows policy evidence as reference material', () => {
    expect(agentItemView(agent[2])).toMatchObject({ title: '업무기준 참고', body: '상속예금 해지·지급 FAQ – 공통 유의사항', source: '업무 문서 참고' })
  })

  it('keeps the message of an unknown item', () => {
    expect(agentItemView({ category: 'NEW_ITEM', message: '내용', source: 'OTHER' })).toEqual({ title: '기타 검토 항목', body: '내용', source: '출처 미확인' })
  })
})

describe('decisionGuide', () => {
  const inReview = staffCase({ caseView: { ...staffCase().caseView, caseStatus: 'IN_REVIEW' } })
  const decision = (notification: unknown) => staffCase({ finalDecision: { notification } as C02StaffCase['finalDecision'] })

  it('follows the stage of the review', () => {
    expect(decisionGuide(staffCase(), '')).toBe('자동 사전점검이 완료됐습니다. 제출된 서류를 확인한 뒤 검토를 시작해 주세요.')
    expect(decisionGuide(inReview, '')).toBe('서류 원본과 상속관계를 확인한 후 검토 결과를 선택해 주세요.')
    expect(decisionGuide(inReview, 'DOCUMENT_INSUFFICIENT')).toBe('추가로 필요한 서류와 보완 사유를 고객에게 안내해 주세요.')
    expect(decisionGuide(staffCase({}, [], 'NOT_RUN'), '')).toBe('먼저 사전점검을 실행해 주세요.')
    expect(decisionGuide(staffCase({}, [], 'FAILED'), '')).toContain('실패')
  })

  it('claims a customer notice only when one was recorded', () => {
    expect(decisionGuide(decision({ notificationId: 'n' }), '')).toBe('검토 결과가 저장되고 고객에게 안내되었습니다.')
    expect(decisionGuide(decision(null), '')).toBe('검토 결과가 저장되었습니다.')
  })
})

describe('C02OcrResults', () => {
  const extracted = JSON.stringify({ fields: { delegator_1_name: '김하나', '2_주소': '서울' }, standard: { depositorName: '김영준', accounts: [{ accountNo: '999' }], reference: { depositorBirthDate: '19550101' } } })
  const documents = [
    { documentFileId: 51, documentVersion: 1, method: 'CLOVA_TEMPLATE', status: 'SUCCEEDED', extracted, failureReason: null, manualVerificationRequired: true },
    { documentFileId: 52, documentVersion: 1, method: 'NONE', status: 'NOT_APPLICABLE', extracted: null, failureReason: null, manualVerificationRequired: true },
  ]
  const render = (items: C02ReviewItem[]) => renderToStaticMarkup(createElement(C02OcrResults, { ocr: { caseId: 8, revision: 6, manualVerificationRequired: true, documents }, intake: staffCase().intake, items }))

  it('labels results without codes and separates extracted from compared values', () => {
    const html = render(ocr)

    expect(html).toContain('일치')
    expect(html).toContain('정본 정보 없음')
    expect(html).toContain('계좌번호를 대조할 은행 기준정보가 없어 직원 확인이 필요합니다.')
    expect(html).toContain('추출됨 · 자동 비교 미실시')
    expect(html).toContain('추출값 없음')
    expect(html).toContain('상속 위임장에 필요한 주요 항목을 확인했습니다.')
    expect(html).toContain('일반 증빙서류 1건은 자동 판독 대상이 아닙니다.')
    expect(html).not.toContain('C02_POA')
    expect(html).not.toContain('AUTO_MATCHED')
  })

  it('shows a held document type without a success sentence', () => {
    expect(render([{ category: 'C02_POA_DOCUMENT_TYPE', result: 'TYPE_UNVERIFIABLE', documentFileId: 51 }])).toContain('위임장 양식을 자동으로 확인하지 못했습니다')
  })
})
