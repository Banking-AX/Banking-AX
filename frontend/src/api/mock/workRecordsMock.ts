// Mock 샘플, 실제 응답이 아니다. Values come from docs/design-ref/MyWork.dc.html (stage=done) and BDetail.dc.html.
import { ApiError } from '../client'
import type { B01RecordDetail, WorkRecordDetail, WorkRecordItem, WorkRecordsAdapter } from '../workRecords'

const CHAT_A = '청약통장 해지 문의 (10.21)'
const CHAT_B = '거래내역·충당순서·채무조정 상담 (10.20)'
const LOAN = 'AX 개인 신용대출 210-***-557731'

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

const ITEMS: WorkRecordItem[] = [
  { recordId: 'b02-hold-demo', tab: 'progress', taskType: 'B', name: '청년 주택드림 청약통장 해지', chatTitle: CHAT_A, requestedAt: '2026-10-21T09:00:00+09:00', updatedAt: '2026-10-21T09:20:00+09:00', status: 'hold', sub: '과세증명서 확인 단계 · 10.24 09:20 초기화 예정', subTone: 'warning' },
  { recordId: 'a02-plan-demo', tab: 'progress', taskType: 'A', name: '대출계좌 정보 조회', chatTitle: CHAT_A, requestedAt: '2026-10-21T09:00:00+09:00', updatedAt: '2026-10-21T09:20:00+09:00', status: 'plan', sub: '아직 시작하지 않은 업무', subTone: 'muted' },
  { recordId: 'b01-pending-demo', tab: 'progress', taskType: 'B', name: '채무변제 충당순서 변경', chatTitle: CHAT_B, requestedAt: '2026-10-20T15:05:00+09:00', updatedAt: '2026-10-20T15:10:00+09:00', status: 'pending', sub: '서명·인증 완료 · 변경 처리 대기', subTone: 'muted', detailPath: '/work/b01-pending-demo' },
  { recordId: 'c01-ok-demo', tab: 'done', taskType: 'C', name: '채무조정 접수', no: 'C-20261020-003', chatTitle: CHAT_B, requestedAt: '2026-10-20T15:39:00+09:00', updatedAt: '2026-10-21T11:05:00+09:00', status: 'ok', sub: '비대면 접수 · 방문 예약 대상 아님' },
  { recordId: 'b01-demo', tab: 'done', taskType: 'B', name: '채무변제 충당순서 변경', chatTitle: CHAT_B, requestedAt: '2026-10-20T15:05:00+09:00', updatedAt: '2026-10-20T15:11:00+09:00', status: 'done', sub: '비용 → 원금 → 이자로 변경 · 이메일 발송', detailPath: '/work/b01-demo' },
  { recordId: 'a01-done-demo', tab: 'done', taskType: 'A', name: '거래내역 조회', chatTitle: CHAT_B, requestedAt: '2026-10-20T15:00:00+09:00', updatedAt: '2026-10-20T15:02:00+09:00', status: 'done', sub: '조회 결과는 대화와 함께 10.27 삭제', subTone: 'muted' },
  { recordId: 'c01-lack-demo', tab: 'closed', taskType: 'C', name: '채무조정 접수', no: 'C-20260912-014', chatExpired: true, requestedAt: '2026-09-12T10:00:00+09:00', updatedAt: '2026-09-15T14:20:00+09:00', status: 'lack', sub: '직원 최종 결과 · 접수 종료', subTone: 'danger' },
  { recordId: 'c01-cancel-demo', tab: 'closed', taskType: 'C', name: '채무조정 접수', no: '미제출', chatExpired: true, requestedAt: '2026-09-01T10:00:00+09:00', updatedAt: '2026-09-04T10:15:00+09:00', status: 'cancel', sub: '3일 동안 진행하지 않아 입력 내용 초기화', subTone: 'muted' },
  { recordId: 'b02-manual-demo', tab: 'closed', taskType: 'B', name: '청년 주택드림 청약통장 해지', chatTitle: CHAT_A, requestedAt: '2026-10-21T14:20:00+09:00', updatedAt: '2026-10-21T14:34:00+09:00', status: 'manual', sub: '자동 해지 중단 · 영업점 상담 안내 메일 발송', subTone: 'warning', detailPath: '/work/b02-manual-demo' },
]

const B01_DONE: B01RecordDetail = {
  kind: 'b01', recordId: 'b01-demo', status: 'DONE', title: '채무변제 충당순서 변경', code: 'B-20261020-011 · B-01', chatTitle: '10.20 거래내역·충당순서·채무조정 상담',
  beforeOrder: 'INTEREST_FIRST', afterOrder: 'PRINCIPAL_FIRST', loanLabel: LOAN,
  targetLoan: `${LOAN} · 잔액 17,200,000원 · 연체 45일 · 기한이익 상실 2026.09.30`,
  excludedLoan: 'AX 전세자금대출 230-***-114820 · 기한이익 미상실이라 변경 대상이 아니에요',
  ruling: '변경 가능 (ELIGIBLE) · 기한의 이익이 상실된 대출 · 처리 직전 규칙 재검증 통과',
  consent: { at: '2026-10-20T15:10:00+09:00', itemCount: 4 },
  processedAt: '2026-10-20T15:11:00+09:00',
  email: { sentAt: '2026-10-20T15:11:00+09:00', maskedEmail: 'min***@example.com' },
  thirdPartyProvider: true,
  basis: 'CM-01 – §11 채무변제충당순서 변경 신청 · B01-01 충당순서 변경 신청서 – 유의사항',
  timeline: [
    { time: '2026-10-20T15:05:00+09:00', text: '충당순서 변경 화면 진입 · 대상 대출 확인', tone: 'gray' },
    { time: '2026-10-20T15:10:00+09:00', text: '유의사항 4개 항목 확인 · 신청서 서명 · 변경 동의', tone: 'gray' },
    { time: '2026-10-20T15:11:00+09:00', text: '규칙 재검증 통과 → 변경 처리 완료', tone: 'green' },
    { time: '2026-10-20T15:11:00+09:00', text: '결과 안내 이메일 발송', tone: 'blue' },
  ],
}

const B01_PENDING: B01RecordDetail = {
  ...B01_DONE,
  recordId: 'b01-pending-demo', status: 'PENDING',
  ruling: '변경 가능 (ELIGIBLE) · 기한의 이익이 상실된 대출',
  processedAt: undefined, email: undefined,
  timeline: B01_DONE.timeline.slice(0, 2),
}

const DETAILS: Record<string, WorkRecordDetail> = {
  'b01-demo': B01_DONE,
  'b01-pending-demo': B01_PENDING,
  'b02-manual-demo': {
    kind: 'b02manual', recordId: 'b02-manual-demo', title: '청년 주택드림 청약통장 해지', code: 'B-20261021-007 · B-02', chatTitle: '10.21 청약통장 해지 문의',
    explanation: '과세증명서상 주택 재산세 과세사실이 있어 청년주택드림 이율 적용 여부를 자동으로 정할 수 없었어요. 해지는 진행되지 않았고 통장은 그대로 유지돼요. 가입 전 매도·명의 불일치·무주택 판단기준 예외에 해당하면 무주택으로 인정될 수 있어 영업점 상담에서 서류를 직접 확인해요.',
    account: '청년 주택드림 청약통장 270-***-903311 · 가입 2023.06.15 · 잔액 3,400,000원',
    stopReason: 'B2-R5 · MANUAL_REVIEW · 가입 2년 이상 + 우대이율 선택 + 과세사실 있음',
    decidedAt: '2026-10-21T14:34:00+09:00',
    email: { sentAt: '2026-10-21T14:34:00+09:00', maskedEmail: 'min***@example.com' },
    documents: ['신분증', '과세 물건지 등기부등본 — 가입 전 매도·명의 불일치를 소명할 때', '무주택 판단기준 해당 입증서류 — 처음부터 무주택으로 인정된다고 소명할 때'],
    basis: 'B02-01 – 이율 자격 입증서류 ②③ · 무주택 판단기준',
    timeline: [
      { time: '2026-10-21T14:20:00+09:00', text: '해지 화면 이어서 진행 · 청년주택드림 이율 선택', tone: 'gray' },
      { time: '2026-10-21T14:31:00+09:00', text: '과세증명서 확인 · 과세사실 있음 (OCR 미적용 · 모의 과세정보)', tone: 'gray' },
      { time: '2026-10-21T14:34:00+09:00', text: '규칙 B2-R5 판정 → 영업점 상담 필요 (MANUAL_REVIEW)', tone: 'amber' },
      { time: '2026-10-21T14:34:00+09:00', text: '상담 필요 안내 이메일 발송', tone: 'blue' },
    ],
  },
}

export function createMockWorkRecordsAdapter(): WorkRecordsAdapter {
  return {
    async list() {
      await delay(400)
      return { asOf: new Date().toISOString(), items: ITEMS.map(item => ({ ...item })), retentionDays: 7, draftResetDays: 3 }
    },
    async getDetail(recordId) {
      await delay(400)
      const detail = Object.prototype.hasOwnProperty.call(DETAILS, recordId) ? DETAILS[recordId] : undefined
      if (!detail) throw new ApiError('WORK_RECORD_NOT_FOUND', 404, 'The work record was not found.')
      return detail
    },
  }
}
