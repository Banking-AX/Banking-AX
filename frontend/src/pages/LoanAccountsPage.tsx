import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { toUserMessage } from '../api/errorMessages'
import { createRealLoanAccountsAdapter, type CustomerLoan, type CustomerLoanList, type RepaymentStep } from '../api/loans'
import { createMockLoanAccountsAdapter, type LoanMockScenario } from '../api/mock/loansMock'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { loanPageGuide } from '../features/chat/chatGuideCopy'
import '../features/loans/loan-accounts.css'

type State = { kind: 'loading' } | { kind: 'ready'; data: CustomerLoanList } | { kind: 'error'; message: string }

const GUIDE = loanPageGuide()
const STEP_LABELS = new Map<RepaymentStep, string>([['COST', '비용'], ['INTEREST', '이자'], ['PRINCIPAL', '원금']])
const ORDER_STEPS = new Map<CustomerLoan['repaymentOrder'], RepaymentStep[]>([
  ['INTEREST_FIRST', ['COST', 'INTEREST', 'PRINCIPAL']],
  ['PRINCIPAL_FIRST', ['COST', 'PRINCIPAL', 'INTEREST']],
])

const pad2 = (value: number) => String(value).padStart(2, '0')
const toNumber = (value: number | string | null) => value === null || (typeof value === 'string' && value.trim() === '') ? NaN : Number(value)
const won = (value: number) => `${value.toLocaleString('ko-KR')}원`

function fullDate(value: string): string {
  // Date-only values (YYYY-MM-DD) are calendar dates; instants are shown in the browser's local date.
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match) return `${match[1]}.${match[2]}.${match[3]}`
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}`
}

function monthDay(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}`
}

function repaymentOrderText(loan: CustomerLoan): string {
  const steps = loan.repaymentOrderSteps.length > 0 ? loan.repaymentOrderSteps : ORDER_STEPS.get(loan.repaymentOrder) ?? []
  return steps.map(step => STEP_LABELS.get(step) ?? step).join(' → ') || '-'
}

function mockScenario(value: string | null): LoanMockScenario | null {
  if (value === '1') return 'sample'
  return value === 'empty' || value === 'error' ? value : null
}

/** A-02 loan account page (docs/design-ref/A02Page.dc.html). Every value comes from the adapter; nothing is fabricated. */
export function LoanAccountsPage() {
  const [params] = useSearchParams()
  // The real API is the default; the Mock sample exists only in DEV with ?mock=1|empty|error.
  // import.meta.env.DEV is a build-time constant, so production bundles drop the Mock adapter entirely.
  const scenario = import.meta.env.DEV ? mockScenario(params.get('mock')) : null
  const adapter = useMemo(() => import.meta.env.DEV && scenario ? createMockLoanAccountsAdapter(scenario) : createRealLoanAccountsAdapter(), [scenario])
  const [state, setState] = useState<State>({ kind: 'loading' })

  const load = useCallback(() => {
    let active = true
    setState({ kind: 'loading' })
    adapter.list()
      .then(data => { if (active) setState({ kind: 'ready', data }) })
      .catch(cause => { if (active) setState({ kind: 'error', message: toUserMessage(cause) }) })
    return () => { active = false }
  }, [adapter])

  useEffect(() => load(), [load])

  return <div className="loan-page">
    <SiteHeader spot />
    <div className="main-layout">
      <div className="main-layout__body">
        <main className="loan-main">
          {import.meta.env.DEV && scenario && <p role="note" className="loan-mock">Mock 샘플 · 서버 연결 전 화면 확인용이에요</p>}
          <div className="loan-intro">
            <nav aria-label="현재 위치" className="loan-breadcrumb"><Link to="/" className="loan-breadcrumb__link">홈</Link><span aria-hidden="true">›</span><span aria-current="page" className="loan-breadcrumb__current">대출계좌 정보 조회</span></nav>
            <div className="loan-title-row"><h1 className="loan-title">대출계좌 정보 조회</h1><span className="loan-badge">A · 조회</span></div>
            <p className="loan-lead">고객님 명의의 AX뱅크 대출 잔액, 금리, 연체 상태를 한곳에서 확인해요. 조회만 하며 이메일·알림은 보내지 않아요.</p>
          </div>

          {state.kind === 'loading' && <p role="status" className="loan-status">대출계좌 정보를 불러오고 있어요.</p>}
          {state.kind === 'error' && <div role="alert" className="loan-alert">
            <span className="loan-alert__text">{state.message}</span>
            <button type="button" onClick={() => load()} className="loan-alert__retry">다시 시도</button>
          </div>}
          {state.kind === 'ready' && <LoanList data={state.data} />}

          <p className="loan-footnote">카드론 등 AX뱅크 대출이 아닌 상품은 조회되지 않아요</p>
          <div className="loan-actions">
            <Link to="/" className="loan-button loan-button--plain">홈으로</Link>
          </div>
        </main>
        <SiteFooter />
      </div>
    </div>
  </div>
}

function LoanList({ data }: { data: CustomerLoanList }) {
  if (data.loans.length === 0) return <p className="loan-empty">조회되는 대출이 없어요.</p>
  const total = data.loans.reduce((sum, loan) => { const value = toNumber(loan.balance); return Number.isFinite(value) ? sum + value : sum }, 0)
  const overdue = data.loans.filter(loan => loan.overdueDays > 0).length
  return <>
    <section aria-label="대출 요약" className="loan-summary">
      <span>대출 <strong>{data.loans.length}건</strong></span>
      <span>총 대출 잔액 <strong>{won(total)}</strong></span>
      <span>연체 중 <strong className={overdue > 0 ? 'loan-summary__danger' : undefined}>{overdue}건</strong></span>
      <span className="loan-summary__asof">{fullDate(data.asOf)} 기준</span>
    </section>
    {data.loans.map(loan => <LoanCard key={loan.loanId} loan={loan} />)}
  </>
}

function LoanCard({ loan }: { loan: CustomerLoan }) {
  const overdue = loan.overdueDays > 0
  const balance = toNumber(loan.balance)
  const rate = toNumber(loan.interestRate)
  const titleId = `loan-${loan.loanId}`
  return <section aria-labelledby={titleId} className={`loan-card${overdue ? ' loan-card--overdue' : ''}`}>
    <div className="loan-card__head">
      <h2 id={titleId} className="loan-card__title">{loan.loanName ?? '대출'}</h2>
      <span className="loan-card__no">{loan.loanNoMasked}</span>
      <span className={`loan-card__badge loan-card__badge--${overdue ? 'overdue' : 'ok'}`}>{overdue ? `연체 ${loan.overdueDays}일` : '연체 없음'}</span>
    </div>
    <dl className="loan-card__dl">
      <dt>대출 잔액</dt><dd className="loan-card__strong">{Number.isFinite(balance) ? won(balance) : '-'}</dd>
      <dt>적용 금리</dt><dd>{Number.isFinite(rate) ? `연 ${rate.toFixed(2)}%` : '-'}</dd>
      <dt>연체</dt><dd className={overdue ? 'loan-card__danger' : undefined}>{overdue ? `${loan.overdueDays}일` : '없음'}</dd>
      <dt>기한의 이익</dt><dd>{loan.accelerationDeclared ? (loan.accelerationDate ? `상실 (${fullDate(loan.accelerationDate)})` : '상실') : '유지'}</dd>
      <dt>충당순서</dt><dd>{repaymentOrderText(loan)}{loan.repaymentOrderChangedAt && <span className="loan-card__sub"> · {monthDay(loan.repaymentOrderChangedAt)} 변경</span>}</dd>
    </dl>
    {overdue && <div className="loan-card__note">{GUIDE.overdueNote}</div>}
  </section>
}
