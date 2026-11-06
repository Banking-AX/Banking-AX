import { Link } from 'react-router-dom'
import type { ChatParameterRequest, ChatTransactionSearch } from '../../api/chat'
import './chat-lookup.css'

// docs/design-ref/ChatLookup.dc.html (a01, edge). Every value comes from the server reply; the period pills are fixed UI.
const PERIOD_PILLS = ['최근 1주', '최근 1개월', '최근 3개월'] as const

const pad2 = (value: number) => String(value).padStart(2, '0')
const toNumber = (value: number | string) => typeof value === 'string' && value.trim() === '' ? NaN : Number(value)

function monthDay(value: string): string {
  const match = /^\d{4}-(\d{2})-(\d{2})/.exec(value)
  if (match) return `${match[1]}.${match[2]}`
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}`
}

function fullDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  return match ? `${match[1]}.${match[2]}.${match[3]}` : value
}

function signedWon(value: number | string, sign: '+' | '−'): string {
  const amount = toNumber(value)
  return Number.isFinite(amount) ? `${sign}${Math.abs(amount).toLocaleString('ko-KR')}` : '-'
}

function Pills({ items, onPick }: { items: readonly string[]; onPick?: (text: string) => void }) {
  if (items.length === 0) return null
  return <div className="chat-lookup-pills">
    {items.map(item => <button key={item} type="button" disabled={!onPick} onClick={() => onPick?.(item)} className="chat-lookup-pill">{item}</button>)}
  </div>
}

/** Result table (최근 몇 건과 합계) or the no-result notice, below the server's answer bubble. */
export function TransactionResultBody({ result, onQuickReply }: { result: ChatTransactionSearch; onQuickReply?: (text: string) => void }) {
  if (result.totalElements === 0) {
    return <>
      <Pills items={result.nextActions ?? []} onPick={onQuickReply} />
      <span className="chat-lookup-note">조회 결과는 이메일·알림을 보내지 않아요.</span>
    </>
  }
  const period = [result.periodLabel, `${monthDay(result.startDate)} ~ ${monthDay(result.endDate)}`, `총 ${result.totalElements}건 중 최근 ${result.transactions.length}건`].filter(Boolean).join(' · ')
  return <>
    <div className="chat-lookup-card">
      <div className="chat-lookup-card__head"><strong>{result.accountName} {result.accountNoMasked}</strong><span className="chat-lookup-card__period">{period}</span></div>
      <table className="chat-lookup-table">
        <caption className="chat-sr-only">최근 거래내역</caption>
        <thead><tr><th scope="col">날짜</th><th scope="col">내용</th><th scope="col" className="chat-lookup-table__amount">거래금액(원)</th></tr></thead>
        <tbody>
          {result.transactions.map(row => <tr key={row.transactionId}>
            <td>{monthDay(row.occurredAt)}</td>
            <td className="chat-lookup-table__text">{row.counterparty ?? row.memo ?? '-'}</td>
            <td className={`chat-lookup-table__amount${row.direction === 'IN' ? ' chat-lookup-table__amount--in' : ''}`}>{signedWon(row.amount, row.direction === 'IN' ? '+' : '−')}</td>
          </tr>)}
        </tbody>
      </table>
      <div className="chat-lookup-card__foot">
        <span>전체 {result.totalElements}건</span>
        <span>입금 합계 {signedWon(result.depositTotal, '+')}</span>
        <span>출금 합계 {signedWon(result.withdrawalTotal, '−')}</span>
      </div>
    </div>
    <Link to={`/tasks/a01/${result.workItemId}`} className="chat-lookup-more">전체 내역 보기
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"></path></svg>
    </Link>
    {/* "기간을 바꿔 다시 조회할 수 있어요" waits until the server allows re-searching a finished A-01 work item. */}
    <span className="chat-lookup-note">조회 결과는 이메일·알림을 보내지 않아요.</span>
    <Pills items={result.nextActions ?? []} onPick={onQuickReply} />
  </>
}

/** Missing period/account or a range longer than a year: the server asks again; pills send the chosen sentence. */
export function ParameterRequestBody({ request, onQuickReply }: { request: ChatParameterRequest; onQuickReply?: (text: string) => void }) {
  const accounts = request.missing.includes('account') && request.accountOptions ? request.accountOptions.map(option => `${option.accountName} ${option.accountNoMasked}`) : []
  const periods = request.reason === 'MISSING' && request.missing.includes('period') ? PERIOD_PILLS : []
  return <>
    <Pills items={accounts} onPick={onQuickReply} />
    <Pills items={periods} onPick={onQuickReply} />
    <span className="chat-lookup-note">{request.earliestDate
      ? `한 번에 조회할 수 있는 기간은 최근 1년(${fullDate(request.earliestDate)} 이후)까지예요.`
      : '조회는 한 번에 최근 1년까지 가능해요.'}</span>
  </>
}
