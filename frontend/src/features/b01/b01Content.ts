import type { B01RuleCode, RepaymentOrder } from '../../api/b01'

// Fixed front-end copy from docs/design-ref/B01Page.dc.html; none of this comes from the server.

export const B01_STEPS = ['대출 선택', '변경 확인·동의', '처리 결과'] as const

export const ORDER_LABELS: Record<RepaymentOrder, string> = {
  INTEREST_FIRST: '비용 → 이자 → 원금',
  PRINCIPAL_FIRST: '비용 → 원금 → 이자',
}

export const ORDER_DESCRIPTIONS: Record<RepaymentOrder, string> = {
  INTEREST_FIRST: '갚은 돈이 이자부터 빠져 원금이 잘 줄지 않아요',
  PRINCIPAL_FIRST: '원금을 먼저 줄여 연체이자가 붙는 원금 잔액을 낮춰요',
}

export function oppositeOrder(order: RepaymentOrder): RepaymentOrder {
  return order === 'INTEREST_FIRST' ? 'PRINCIPAL_FIRST' : 'INTEREST_FIRST'
}

// ELIGIBLE and NOT_ACCELERATED are design copy; the other three are new wording.
export const RULE_REASONS: Record<B01RuleCode, string> = {
  ELIGIBLE: '기한의 이익이 상실된 대출이에요 (ELIGIBLE)',
  NOT_ACCELERATED: '기한의 이익이 상실되지 않은 대출은 변경 대상이 아니에요 (NOT_ACCELERATED)',
  OUTSOURCED_LOAN: '외부 위탁 대출이라 여기서 변경할 수 없어요. 영업점에 문의해 주세요.',
  AGREEMENT_LOAN: '약정이 있는 대출이라 여기서 변경할 수 없어요. 영업점에 문의해 주세요.',
  ALREADY_APPLIED: '이미 선택한 충당순서로 적용되어 있어요.',
}

export const RULE_BASIS = 'CM-01 – §11 채무변제충당순서 변경 신청'

export const THIRD_PARTY_NOTICE = '연대보증인이 있는 대출이에요. 이 화면은 보증인의 실제 서명·동의를 확인하지 않으며, 직원 확인 주의 항목으로 함께 기록돼요.'

export const NOTICE_TITLE = '유의사항 (B01-01 원문 기준)'

/** Item 3 embeds the original order label in bold, so it is split around it. */
export const NOTICE_ITEMS = [
  { text: '순서를 바꿔도 남은 원금에는 연체이자가 계속 붙어요.' },
  { text: '연체정보 등록과 법적 절차는 변경 전과 똑같이 적용돼요.' },
  { text: '연체된 이자·분할상환금을 모두 갚아 기한의 이익이 다시 생기거나, 만기가 지난 대출의 기한을 연장하거나, 그 밖의 사유로 기한의 이익이 다시 생기면 별도 신청 없이 ', strong: ORDER_LABELS.INTEREST_FIRST, after: '으로 자동으로 돌아가요.' },
  { text: '바꾼 순서를 직접 되돌리는 재변경은 영업점에서만 할 수 있어요.' },
] as const

export const NOTICE_BASIS = 'B01-01 충당순서 변경 신청서 – 유의사항 · 채무변제충당순서의 자동 변경'

export const PENDING_TITLE = '서명과 인증을 마쳤어요'
export const PENDING_SUBTITLE = '충당순서 변경은 아직 처리되지 않았어요.'
