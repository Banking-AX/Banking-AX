// 미리보기용 Mock 샘플이며 실제 응답이 아니다. Values are copied from docs/design-ref/ChatConsent.dc.html.
import type { ReactNode } from 'react'
import type { B01Result } from '../../api/b01'
import { ORDER_LABELS } from '../b01/b01Content'
import type { ChatBGuideLoan } from './ChatBGuide'
import type { ChatTaskState } from './ChatTaskStatusBar'

export const CHAT_B_GUIDE_TIME = '오후 3:03'
export const CHAT_B_RETURN_TIME = '오후 3:11'

// AI 가 만든 답변이 아니라 샘플: the design's explanation text, kept verbatim.
export const CHAT_B_PARAGRAPHS: ReactNode[] = [
  <>충당순서 변경은 연체된 대출에 일부만 갚을 때, 그 돈이 이자보다 <strong>원금에 먼저</strong> 쓰이도록 순서를 바꾸는 업무예요. 기본 순서는 비용 → 이자 → 원금이고, 바꾸면 비용 → 원금 → 이자가 돼요.</>,
  '다만 남은 원금에는 연체이자가 계속 붙고, 연체정보 등록과 법적 절차는 바꾸기 전과 똑같이 적용돼요.',
]

export const CHAT_B_CITATIONS = ['CM-01 상품설명서 – §11 채무변제충당순서 변경 신청', 'B01-01 변경 신청서 – 유의사항']

export const CHAT_B_LOANS: ChatBGuideLoan[] = [
  { loanId: 'l1', title: 'AX 개인 신용대출 210-***-557731', detail: '연체 45일 · 기한의 이익 상실(2026.09.30) · 현재 비용 → 이자 → 원금', eligible: true },
  { loanId: 'l2', title: 'AX 전세자금대출 230-***-114820', detail: '연체 없음 · 기한의 이익 유지', eligible: false },
]

export const CHAT_B_RESULT_LABELS = {
  loanLabel: 'AX 개인 신용대출 210-***-557731',
  beforeLabel: ORDER_LABELS.INTEREST_FIRST,
  afterLabel: ORDER_LABELS.PRINCIPAL_FIRST,
}

export const CHAT_B_RESULT_DONE: B01Result = { status: 'DONE', processedAt: '2026-10-20T15:11:00+09:00', maskedEmail: 'min***@example.com' }
export const CHAT_B_RESULT_PENDING: B01Result = { status: 'PENDING' }

export const CHAT_B_NEXT = { label: '채무조정 접수', stateLabel: '대기 · 남은 업무 1건' }

export function chatBTasks(b01State: ChatTaskState): { label: string; state: ChatTaskState }[] {
  return [
    { label: '거래내역 조회', state: '완료' },
    { label: '충당순서 변경', state: b01State },
    { label: '채무조정 접수', state: '대기' },
  ]
}
