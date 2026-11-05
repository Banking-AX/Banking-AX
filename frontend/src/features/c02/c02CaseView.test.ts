import { describe, expect, it } from 'vitest'
import type { C02CaseIntake, C02Document, C02DocumentSlot } from '../../api/taskApi'
import { caseNumber, casePill, groupDocuments, slotKey, timelineSteps, uploadProgress, visitConfirmation, visitLabel } from './c02CaseView'

const intake: C02CaseIntake = {
  decedentName: '이정호', decedentBirthDate: '1958-02-11', deathDate: '2026-09-28',
  heirs: [
    { personRef: 'h1', name: '정미숙', relationship: '배우자', applicant: false, visitStatus: 'ATTENDING', delegationMethod: 'NONE' },
    { personRef: 'h2', name: '이서연', relationship: '자녀', applicant: true, visitStatus: 'ATTENDING', delegationMethod: 'NONE' },
  ],
}
const slots: C02DocumentSlot[] = [
  { documentTypeCode: 'DECEDENT_DETAILED_FAMILY_RELATION', requirement: 'REQUIRED', personRef: null, note: '' },
  { documentTypeCode: 'HEIR_IDENTITY_CARD', requirement: 'REQUIRED', personRef: 'h1', note: '' },
  { documentTypeCode: 'HEIR_IDENTITY_CARD', requirement: 'REQUIRED', personRef: 'h2', note: '' },
  { documentTypeCode: 'PASSBOOK_AND_SEAL', requirement: 'VISIT_ONLY', personRef: null, note: '' },
]
const doc = (documentSlotKey: string, version: number): C02Document => ({ documentFileId: version, caseId: 2, caseRevision: 1, documentSlotKey, version, fileName: `f${version}.pdf`, mimeType: 'application/pdf', sizeBytes: 1, status: 'UPLOADED', documentType: 'X', uploadedAt: '2026-10-20T01:00:00Z' })

describe('C-02 case view', () => {
  it('uses the server slot key format with COMMON for shared slots', () => {
    expect(slotKey(slots[0])).toBe('DECEDENT_DETAILED_FAMILY_RELATION:COMMON')
    expect(slotKey(slots[1])).toBe('HEIR_IDENTITY_CARD:h1')
  })

  it('groups decedent, per-heir and visit-only documents and keeps the latest version', () => {
    const groups = groupDocuments(slots, [doc('HEIR_IDENTITY_CARD:h1', 1), doc('HEIR_IDENTITY_CARD:h1', 3)], intake)
    expect(groups.common).toHaveLength(1)
    expect(groups.visitOnly.map(row => row.slot.documentTypeCode)).toEqual(['PASSBOOK_AND_SEAL'])
    expect(groups.byHeir[0].rows[0].title).toBe('정미숙 상속인 신분증')
    expect(groups.byHeir[0].rows[0].uploaded?.version).toBe(3)
    expect(uploadProgress(groups)).toEqual({ uploaded: 1, required: 3, missingRequired: 2 })
  })

  it('never shows the AI precheck as staff approval', () => {
    expect(casePill('SUBMITTED', 'RUNNING').label).toBe('접수 완료 · AI 자동 점검 중')
    expect(casePill('SUBMITTED', 'READY_FOR_STAFF').label).toBe('접수 완료 · 직원 검토 대기')
    expect(casePill('IN_REVIEW', 'READY_FOR_STAFF').label).toBe('직원 검토 중')
    expect(casePill('REVIEW_PASSED', 'READY_FOR_STAFF').label).toBe('진행 가능')
  })

  it('marks the result step done only when a notified staff decision exists', () => {
    const pending = timelineSteps({ caseStatus: 'SUBMITTED', reviewStatus: 'READY_FOR_STAFF', finalDecision: null }, null)
    expect(pending.at(-1)?.state).toBe('pending')
    expect(pending[2].state).toBe('done')
    const decided = timelineSteps({ caseStatus: 'REVIEW_PASSED', reviewStatus: 'READY_FOR_STAFF', finalDecision: { outcome: 'PROCEED', customerMessage: 'ok' } }, null)
    expect(decided.at(-1)).toMatchObject({ label: '검토 결과 · 진행 가능', state: 'done' })
  })

  it('labels a provisional absence as unconfirmed', () => {
    expect(visitLabel({ visitStatus: 'ABSENT', delegationMethod: 'RECEIPT' })).toBe('불참 · 위임 (AX뱅크 앱 위임장)')
    expect(visitConfirmation({ visitStatus: 'UNCONFIRMED_ABSENT' })).toBe('잠정 불참 · 미확인')
  })

  it('derives a display receipt number from id and creation date', () => {
    expect(caseNumber(2, '2026-10-20T01:42:00Z')).toMatch(/^C-202610(20|21)-002$/)
    expect(caseNumber(7, null)).toBe('C-00000000-007')
  })
})
