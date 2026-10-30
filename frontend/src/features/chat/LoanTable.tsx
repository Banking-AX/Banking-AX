import type { LoanSummary } from '../../api/chat'

const REPAYMENT_ORDER_LABELS = new Map<string, string>([
  ['INTEREST_FIRST', '비용 → 이자 → 원금'],
  ['PRINCIPAL_FIRST', '비용 → 원금 → 이자'],
])

function formatWon(value: number | string): string {
  const amount = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  return Number.isFinite(amount) ? `${amount.toLocaleString('ko-KR')}원` : '-'
}

// Acceleration date and repayment-order change date are not in the server response, so they are not shown.
export function LoanTable({ loans }: { loans: LoanSummary[] }) {
  // Focusable so keyboard users can scroll the table horizontally with arrow keys.
  return <div className="chat-loan-scroll" tabIndex={0} role="region" aria-label="대출계좌 목록, 좌우로 스크롤할 수 있어요">
    <table className="chat-loan-table">
      <caption className="chat-sr-only">대출계좌 목록</caption>
      <thead>
        <tr>
          <th scope="col" className="chat-loan-th">대출</th>
          <th scope="col" className="chat-loan-th">잔액</th>
          <th scope="col" className="chat-loan-th">연체</th>
          <th scope="col" className="chat-loan-th">기한의 이익</th>
          <th scope="col" className="chat-loan-th">충당순서</th>
        </tr>
      </thead>
      <tbody>
        {loans.map(loan => <tr key={loan.loanId}>
          <td className="chat-loan-td"><strong>{loan.loanName}</strong><br /><span className="chat-loan-sub">{loan.loanNoMasked}</span></td>
          <td className="chat-loan-td">{formatWon(loan.balance)}</td>
          <td className={`chat-loan-td${loan.overdueDays > 0 ? ' chat-loan-overdue' : ''}`}>{loan.overdueDays > 0 ? `${loan.overdueDays}일` : '없음'}</td>
          <td className="chat-loan-td">{loan.accelerationDeclared ? '상실' : '유지'}</td>
          <td className="chat-loan-td">{REPAYMENT_ORDER_LABELS.get(loan.repaymentOrder) ?? '-'}</td>
        </tr>)}
      </tbody>
    </table>
  </div>
}
