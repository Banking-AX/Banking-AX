import type { LoanSummary } from '../../api/chat'

function formatWon(value: number | string): string {
  const amount = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  return Number.isFinite(amount) ? `${amount.toLocaleString('ko-KR')}원` : '-'
}

/**
 * A-02 result rows (docs/design-ref/ChatLookup.dc.html): loan name, masked number, balance and overdue badge.
 * Rate, acceleration and repayment order are left to the loan account page (row 3-A); every value is from the server.
 */
export function LoanTable({ loans }: { loans: LoanSummary[] }) {
  return <ul className="chat-loan-list" aria-label="대출계좌 목록">
    {loans.map(loan => <li key={loan.loanId} className="chat-loan-row">
      <span className="chat-loan-row__text">
        <strong className="chat-loan-row__name">{loan.loanName}</strong>
        <span className="chat-loan-row__meta">{loan.loanNoMasked} · 잔액 {formatWon(loan.balance)}</span>
      </span>
      <span className={`chat-loan-badge chat-loan-badge--${loan.overdueDays > 0 ? 'overdue' : 'ok'}`}>
        {loan.overdueDays > 0 ? `연체 ${loan.overdueDays}일` : '연체 없음'}
      </span>
    </li>)}
  </ul>
}
