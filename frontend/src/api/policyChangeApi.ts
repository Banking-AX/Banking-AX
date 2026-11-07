import { apiFetch } from './client'

/** Mirrors PolicyChangeReviewService. Bank facts sit in `change`; Banking-AX's own state sits beside it. */
export type PolicyChange = {
  sourceSystem: string; changeEventId: string; externalPolicyId: string; sourceVersion: string; versionOrder: number
  previousVersion: string | null; policyCategory: string; changeType: string; affectedTaskCodes: string[]
  publishedAt: string; effectiveAt: string; sourceStatus: string; changeSummary: string
  sourceReference: { documentId: string; title: string | null; uri: string | null }
  detail: { fictional?: boolean; before?: string | null; after: string; addedCheckItems?: string[] }
}
export type PolicyDecision = 'NO_ACTION' | 'NOTICE_APPROVED' | 'DEV_REQUIRED'
export type DecisionView = { decision: PolicyDecision; staffId: number; decidedAt: string; note: string | null }
export type ChangeView = {
  id: number; change: PolicyChange; intakeStatus: string; reviewStatus: string; effectiveState: string
  ruleChangeReviewRequired: boolean; versionGap: boolean; mappedTaskCodes: string[]; unmappedTaskCodes: string[]
  receivedAt: string; decision: DecisionView | null
}
export type OperationEntry = { id: number; eventType: string; value: string | null; actorType: string; payload: Record<string, unknown>; at: string }
export type PolicyOverview = { changes: ChangeView[]; operations: OperationEntry[] }
export type CaseImpact = {
  caseId: number; revision: number; caseStatus: string; submittedAt: string | null; ruleVersion: string | null
  policyVersionAtSubmission: string | null; submittedBeforeEffective: boolean; reasonCodes: string[]
}
export type TaskImpact = { taskCode: string; openCaseCount: number; candidates: CaseImpact[] }
export type PolicyAnalysis = {
  status: 'SUCCEEDED' | 'FAILED'; code?: string; analyzedAt: string; method?: 'LLM' | 'RULE_FALLBACK'; model?: string | null
  summary?: string; changePoints?: { text: string }[]
  relatedTasks?: { taskCode: string; reason: string; bankDesignated: boolean }[]
  recheckItems?: { text: string; caseIds: number[] }[]; customerNoticeHint?: string
  evidence?: { docId: string; title: string; section: string }[]; droppedClaims?: number
}
export type VersionRow = {
  id: number; sourceVersion: string; versionOrder: number; previousVersion: string | null; intakeStatus: string
  versionGap: boolean; effectiveAt: string; receivedAt: string
}
export type PolicyDetail = {
  view: ChangeView; versions: VersionRow[]; impact: TaskImpact[]; analysis: PolicyAnalysis | null; audit: OperationEntry[]
}
export type SyncResult = { fetched: number; accepted: number; stale: number; outOfScope: number; quarantined: number; duplicate: number }
export type PolicyNotice = {
  id: number; sourceVersion: string; summary: string; effectiveState: string; effectiveAt: string
  ruleChangeReviewRequired: boolean; newerBankVersion: string | null; reasonCodes: string[]
}
export type PolicyNotices = { taskCode: string; caseId: number | null; approved: PolicyNotice[]; pending: PolicyNotice[] }

const BASE = '/api/v1/staff/policy-changes'
const rid = (action: string) => `policy-${action}-${crypto.randomUUID()}`
const post = <T>(path: string, body: Record<string, unknown>) => apiFetch<T>(path, { method: 'POST', body: JSON.stringify(body) })

export const policyChangeApi = {
  overview: () => apiFetch<PolicyOverview>(`${BASE}?requestId=${rid('list')}`),
  detail: (id: number) => apiFetch<PolicyDetail>(`${BASE}/${id}?requestId=${rid('detail')}`),
  notices: (taskCode: string, caseId?: number) => apiFetch<PolicyNotices>(
    `${BASE}/notices?taskCode=${encodeURIComponent(taskCode)}${caseId === undefined ? '' : `&caseId=${caseId}`}&requestId=${rid('notices')}`),
  sync: () => post<SyncResult>(`${BASE}/sync`, { requestId: rid('sync') }),
  analyze: (id: number) => post<PolicyAnalysis>(`${BASE}/${id}/analysis`, { requestId: rid('analysis') }),
  decide: (id: number, decision: PolicyDecision, note: string) =>
    post<DecisionView>(`${BASE}/${id}/decision`, { requestId: rid('decision'), decision, note: note.trim() || null }),
}

export const TASK_LABELS: Record<string, string> = {
  TRANSACTION_SEARCH: 'A-01 거래내역 조회', LOAN_INQUIRY: 'A-02 대출계좌 조회', REPAYMENT_ORDER_CHANGE: 'B-01 충당순서 변경',
  HOUSING_SAVINGS_CLOSE: 'B-02 청약통장 해지', DEBT_ADJUSTMENT: 'C-01 채무조정 접수', INHERITANCE_DEPOSIT: 'C-02 상속예금 접수',
}
export const taskLabel = (code: string) => TASK_LABELS[code] ?? code

export const CHANGE_TYPE_LABELS: Record<string, string> = {
  INFORMATION_UPDATE: '안내정보 변경', ELIGIBILITY_RULE_CHANGE: '대상·제외 조건 변경', DOCUMENT_REQUIREMENT_CHANGE: '제출서류 요건 변경',
  PROCEDURE_CHANGE: '업무처리 절차 변경', REVIEW_CRITERIA_CHANGE: '직원 확인사항 변경',
}
export const REASON_LABELS: Record<string, string> = {
  TASK_CODE_MATCH: '같은 업무의 진행 중 Case', DELEGATION_METHOD_MATCH: '변경 대상 위임 방식 사용',
  UNCONFIRMED_ABSENT_HEIR: '방문 여부 미응답 상속인 있음', REQUIRED_DOCUMENT_MATCH: '변경 대상 서류 요구',
}
export const needsReview = (row: ChangeView) => row.reviewStatus === 'PENDING_REVIEW'
