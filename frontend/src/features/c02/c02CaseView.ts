import type { C02CaseIntake, C02CaseTimeline, C02CustomerCaseSnapshot, C02Document, C02DocumentSlot, C02HeirView } from '../../api/taskApi'
import { c02DocumentLabel } from '../../ui/customerLabels'

/** Must match the server's DocumentSlot.key(): common slots use "COMMON", never an empty person reference. */
export const slotKey = (slot: Pick<C02DocumentSlot, 'documentTypeCode' | 'personRef'>) => `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`

/** The server answers NOT_RUN for a revision with no precheck, so a matching revision alone proves nothing. */
export const hasCurrentReview = (precheck: { revision: number; agentRunStatus: string } | null | undefined, revision: number | undefined) =>
  precheck != null && precheck.revision === revision && precheck.agentRunStatus !== 'NOT_RUN'

/** Same format as the server's notification text. */
export const reservationNumber = (id: number) => `V-${String(id).padStart(6, '0')}`

export type Tone = 'info' | 'progress' | 'success' | 'warn' | 'danger' | 'muted'
export type StatusPill = { label: string; tone: Tone }

const pad2 = (value: number) => String(value).padStart(2, '0')

/** Display-only receipt number; the DB has no case_number column, so it is derived from id and creation date. */
export function caseNumber(caseId: number, createdAt: string | null | undefined): string {
  const date = createdAt ? new Date(createdAt) : null
  const day = date && !Number.isNaN(date.getTime()) ? `${date.getFullYear()}${pad2(date.getMonth() + 1)}${pad2(date.getDate())}` : '00000000'
  return `C-${day}-${String(caseId).padStart(3, '0')}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

export function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10).replaceAll('-', '.') : '—'
}

const RESULT_PILL: Record<string, StatusPill> = {
  REVIEW_PASSED: { label: '진행 가능', tone: 'success' },
  REVIEW_REJECTED_REQUIREMENT: { label: '요건 미충족', tone: 'danger' },
  REVIEW_REJECTED_DOCUMENT: { label: '서류 부족', tone: 'danger' },
  CANCELED: { label: '접수 취소', tone: 'muted' },
}

/**
 * case_status (customer/staff intake) and review_status (AI precheck) are independent;
 * READY_FOR_STAFF only means the precheck finished, never staff approval.
 */
export function casePill(caseStatus: string, reviewStatus: string | null): StatusPill {
  if (RESULT_PILL[caseStatus]) return RESULT_PILL[caseStatus]
  if (caseStatus === 'DRAFT') return { label: '작성 중', tone: 'muted' }
  if (caseStatus === 'IN_REVIEW') return { label: '직원 검토 중', tone: 'progress' }
  if (reviewStatus === 'WAITING_CUSTOMER') return { label: '접수 완료 · 추가 확인 필요', tone: 'warn' }
  if (reviewStatus === 'READY_FOR_STAFF' || reviewStatus === 'FAILED_NEEDS_STAFF') return { label: '접수 완료 · 직원 검토 대기', tone: 'info' }
  return { label: '접수 완료 · AI 자동 점검 중', tone: 'info' }
}

/** service_case.updated_at is not touched by uploads, so the newest document upload also counts as activity. */
export function lastActivityAt(snapshot: Pick<C02CustomerCaseSnapshot, 'timeline' | 'documents'>): string | null {
  const times = [snapshot.timeline?.updatedAt, ...snapshot.documents.map(document => document.uploadedAt)]
    .filter((value): value is string => Boolean(value)).map(value => new Date(value).getTime()).filter(value => !Number.isNaN(value))
  return times.length ? new Date(Math.max(...times)).toISOString() : null
}

export function isSubmitted(caseStatus: string): boolean {
  return caseStatus !== 'DRAFT'
}

export type TimelineStep = { label: string; at: string | null; state: 'done' | 'current' | 'pending'; note?: string }

/** Intake/review milestones only; reservation progress is shown in its own card, as in the reference. */
export function timelineSteps(snapshot: Pick<C02CustomerCaseSnapshot, 'caseStatus' | 'reviewStatus' | 'finalDecision'>, timeline: C02CaseTimeline | null): TimelineStep[] {
  const t = timeline
  const submitted = isSubmitted(snapshot.caseStatus)
  const precheckDone = ['READY_FOR_STAFF', 'FAILED_NEEDS_STAFF', 'WAITING_CUSTOMER'].includes(snapshot.reviewStatus ?? '')
  const reviewStarted = Boolean(t?.reviewStartedAt) || ['IN_REVIEW', 'REVIEW_PASSED', 'REVIEW_REJECTED_REQUIREMENT', 'REVIEW_REJECTED_DOCUMENT'].includes(snapshot.caseStatus)
  const decided = Boolean(snapshot.finalDecision)
  const resultLabel = snapshot.finalDecision ? `검토 결과 · ${RESULT_PILL[snapshot.caseStatus]?.label ?? '등록됨'}` : '검토 결과'
  const steps: TimelineStep[] = [
    { label: '업무 요청', at: t?.createdAt ?? null, state: 'done' },
    { label: '접수', at: t?.submittedAt ?? null, state: submitted ? 'done' : 'current' },
    { label: 'AI 자동 점검', at: null, state: !submitted ? 'pending' : precheckDone || reviewStarted ? 'done' : 'current',
      note: snapshot.reviewStatus === 'WAITING_CUSTOMER' ? '추가 확인 요청' : snapshot.reviewStatus === 'FAILED_NEEDS_STAFF' ? '자동 점검 실패 · 직원이 직접 확인' : undefined },
    { label: '직원 검토 시작', at: t?.reviewStartedAt ?? null, state: reviewStarted ? (decided ? 'done' : 'current') : 'pending' },
    { label: resultLabel, at: t?.resultNotifiedAt ?? t?.decidedAt ?? null, state: decided ? 'done' : 'pending', note: decided ? '웹 알림 발송' : undefined },
  ]
  if (snapshot.caseStatus === 'CANCELED') steps.push({ label: '접수 취소', at: t?.canceledAt ?? null, state: 'done' })
  return steps
}

const VISIT_LABEL: Record<string, string> = { ATTENDING: '방문 예정', ABSENT: '불참 · 위임', UNCONFIRMED_ABSENT: '잠정 불참', UNKNOWN: '확인 전' }
const DELEGATION_LABEL: Record<string, string> = { DOCUMENT: '서류로 위임', RECEIPT: 'AX뱅크 앱 위임장', NONE: '' }

export function visitLabel(heir: Pick<C02HeirView, 'visitStatus' | 'delegationMethod'>): string {
  const base = VISIT_LABEL[heir.visitStatus] ?? '확인 전'
  const delegation = DELEGATION_LABEL[heir.delegationMethod]
  return delegation ? `${base} (${delegation})` : base
}

/** UNCONFIRMED_ABSENT is a provisional value for document guidance, never a confirmed absence. */
export function visitConfirmation(heir: Pick<C02HeirView, 'visitStatus'>): string {
  return heir.visitStatus === 'UNCONFIRMED_ABSENT' ? '잠정 불참 · 미확인' : '—'
}

export function heirCaption(heir: Pick<C02HeirView, 'relationship' | 'applicant'>): string {
  return [heir.relationship || '관계 미입력', heir.applicant ? '신청인' : null].filter(Boolean).join(' · ')
}

export type DocumentRow = { key: string; slot: C02DocumentSlot; title: string; owner: string | null; uploaded: C02Document | null }
export type DocumentGroups = { common: DocumentRow[]; byHeir: { heir: C02HeirView | null; personRef: string; rows: DocumentRow[] }[]; visitOnly: DocumentRow[] }

/** Splits slots like the reference: decedent documents, per-heir documents, and items only checked at the visit. */
export function groupDocuments(slots: C02DocumentSlot[], documents: C02Document[], intake: C02CaseIntake | null): DocumentGroups {
  const heirs = new Map((intake?.heirs ?? []).map(heir => [heir.personRef, heir]))
  const latest = new Map<string, C02Document>()
  for (const document of documents) {
    const current = latest.get(document.documentSlotKey)
    if (!current || document.version > current.version) latest.set(document.documentSlotKey, document)
  }
  const groups: DocumentGroups = { common: [], byHeir: [], visitOnly: [] }
  for (const slot of slots) {
    const key = slotKey(slot)
    const heir = slot.personRef ? heirs.get(slot.personRef) ?? null : null
    const owner = slot.personRef ? heir?.name || '상속인' : null
    const row: DocumentRow = { key, slot, title: owner ? `${owner} ${c02DocumentLabel(slot.documentTypeCode)}` : c02DocumentLabel(slot.documentTypeCode), owner, uploaded: latest.get(key) ?? null }
    if (slot.requirement === 'VISIT_ONLY') groups.visitOnly.push(row)
    else if (!slot.personRef) groups.common.push(row)
    else {
      let bucket = groups.byHeir.find(item => item.personRef === slot.personRef)
      if (!bucket) { bucket = { heir, personRef: slot.personRef, rows: [] }; groups.byHeir.push(bucket) }
      bucket.rows.push(row)
    }
  }
  return groups
}

export function uploadProgress(groups: DocumentGroups): { uploaded: number; required: number; missingRequired: number } {
  const rows = [...groups.common, ...groups.byHeir.flatMap(group => group.rows)]
  const required = rows.filter(row => row.slot.requirement === 'REQUIRED')
  return { uploaded: rows.filter(row => row.uploaded).length, required: required.length, missingRequired: required.filter(row => !row.uploaded).length }
}
