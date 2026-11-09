// 미리보기용 Mock 샘플이며 실제 응답이 아니다. Shapes follow the A-01 contract draft in api/chat.ts.
import type { ChatReply } from '../../api/chat'

export const CHAT_LOOKUP_SAMPLES: { name: string; reply: ChatReply }[] = [
  { name: '조건 확인 · 기간 없음', reply: { kind: 'parameterRequest', answer: 'AX 입출금통장 110-***-482913의 거래내역을 조회할게요. 어느 기간을 볼까요?',
    request: { taskCode: 'TRANSACTION_SEARCH', missing: ['period'], reason: 'MISSING', earliestDate: '2025-10-20', accountOptions: null } } },
  { name: '조건 확인 · 계좌 선택', reply: { kind: 'parameterRequest', answer: '어느 계좌의 거래내역을 볼까요?',
    request: { taskCode: 'TRANSACTION_SEARCH', missing: ['account'], reason: 'MISSING', earliestDate: null,
      accountOptions: [{ accountId: 3, accountNoMasked: '110-***-482913', accountName: 'AX 입출금통장' }, { accountId: 4, accountNoMasked: '120-***-305177', accountName: 'AX 저축예금' }] } } },
  { name: '결과', reply: { kind: 'transactions', answer: '최근 1개월 거래내역을 조회했어요.',
    result: { workItemId: 21, accountId: 3, accountNoMasked: '110-***-482913', accountName: 'AX 입출금통장', startDate: '2026-09-21', endDate: '2026-10-20',
      periodLabel: '최근 1개월', totalElements: 6, depositTotal: 2100000, withdrawalTotal: 421800, keyword: null, nextActions: null,
      transactions: [
        { transactionId: 1, occurredAt: '2026-10-19T03:10:00Z', direction: 'OUT', amount: 8400, counterparty: '편의점 결제', memo: null },
        { transactionId: 2, occurredAt: '2026-10-17T01:00:00Z', direction: 'OUT', amount: 142000, counterparty: '관리비 자동이체', memo: null },
        { transactionId: 3, occurredAt: '2026-10-15T01:00:00Z', direction: 'OUT', amount: 186400, counterparty: '대출이자 출금', memo: null },
      ] } } },
  { name: '결과 없음', reply: { kind: 'transactions', answer: '10.12(월)~10.18(일) AX 입출금통장 110-***-482913에서 거래 상대방 ‘스타벅스’로 찾은 출금 거래가 없어요.',
    result: { workItemId: 22, accountId: 3, accountNoMasked: '110-***-482913', accountName: 'AX 입출금통장', startDate: '2026-10-12', endDate: '2026-10-18',
      periodLabel: null, totalElements: 0, depositTotal: 0, withdrawalTotal: 0, keyword: '스타벅스', transactions: [],
      nextActions: ['기간을 1개월로 넓히기', '다른 계좌에서 찾기', '지난주 전체 거래 보기'] } } },
  { name: '범위 초과', reply: { kind: 'parameterRequest', answer: '한 번에 조회할 수 있는 기간은 최근 1년까지예요. 2025.10.20 이후로 기간을 다시 알려 주세요.',
    request: { taskCode: 'TRANSACTION_SEARCH', missing: ['period'], reason: 'RANGE_TOO_LONG', earliestDate: '2025-10-20', accountOptions: null } } },
]
