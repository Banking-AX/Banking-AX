import { useState } from 'react'
import { useParams } from 'react-router-dom'

type Direction = '' | 'IN' | 'OUT'
type SortOrder = 'RECENT' | 'OLDEST'
type Transaction = { transactionId: number; occurredAt: string; direction: 'IN'|'OUT'; amount: number; counterparty: string | null; memo: string | null }
type Result = { accountId: number; accountNoMasked: string; accountName: string; timeZone: string; transactions: Transaction[]; page: number; size: number; totalElements: number; totalPages: number }

/** A-01 displays only Customer Agent/Spring results; it contains no locally invented ledger data. */
export function TransactionSearchPage() {
  const { workItemId } = useParams()
  const [accountId, setAccountId] = useState('1001'); const [startDate, setStartDate] = useState('2026-10-01'); const [endDate, setEndDate] = useState('2026-10-31'); const [transactionType, setTransactionType] = useState<Direction>(''); const [minimumAmount, setMinimumAmount] = useState(''); const [maximumAmount, setMaximumAmount] = useState(''); const [keyword, setKeyword] = useState(''); const [sortOrder, setSortOrder] = useState<SortOrder>('RECENT'); const [result, setResult] = useState<Result | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  const search = async (page = 0) => {
    if (!workItemId || !accountId || !startDate || !endDate) { setError('업무·계좌·조회 기간을 확인해 주세요.'); return }
    setBusy(true); setError(null)
    try {
      const response = await fetch(`${import.meta.env.VITE_AI_BASE_URL ?? ''}/api/v1/customer-agent/execute`, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ taskCode: 'TRANSACTION_SEARCH', requestId: `a01-ui-${workItemId}-${page}`, workItemId: Number(workItemId), customerId: 1, accountId: Number(accountId), startDate, endDate, transactionType: transactionType || null, minimumAmount: minimumAmount || null, maximumAmount: maximumAmount || null, keyword: keyword || null, sortOrder, page, size: 20 }) })
      const payload = await response.json()
      if (!response.ok) throw new Error(payload.detail ?? payload.error?.message ?? '거래내역을 조회할 수 없습니다.')
      if (payload.error) throw new Error(payload.error.code)
      setResult(payload.data as Result)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '거래내역을 조회할 수 없습니다.') } finally { setBusy(false) }
  }
  return <section><h1>거래내역 조회</h1><p>본인 계좌의 기간·입출금 조건을 조회합니다. 화면은 서버 결과만 표시합니다.</p>
    <label>계좌 ID <input inputMode="numeric" value={accountId} onChange={event => setAccountId(event.target.value)} /></label>
    <label>시작일 <input type="date" value={startDate} onChange={event => setStartDate(event.target.value)} /></label>
    <label>종료일 <input type="date" value={endDate} onChange={event => setEndDate(event.target.value)} /></label>
    <label>유형 <select value={transactionType} onChange={event => setTransactionType(event.target.value as Direction)}><option value="">전체</option><option value="IN">입금</option><option value="OUT">출금</option></select></label>
    <label>최소 금액 <input inputMode="decimal" value={minimumAmount} onChange={event => setMinimumAmount(event.target.value)} /></label>
    <label>최대 금액 <input inputMode="decimal" value={maximumAmount} onChange={event => setMaximumAmount(event.target.value)} /></label>
    <label>적요/상대방 <input value={keyword} onChange={event => setKeyword(event.target.value)} /></label>
    <label>정렬 <select value={sortOrder} onChange={event => setSortOrder(event.target.value as SortOrder)}><option value="RECENT">최신순</option><option value="OLDEST">과거순</option></select></label>
    <button type="button" disabled={busy} onClick={() => search(0)}>{busy ? '조회 중' : '조회'}</button>
    {error && <p role="alert">{error}</p>}
    {result && <><h2>{result.accountName} ({result.accountNoMasked})</h2><p>{result.timeZone} 기준 · {result.totalElements}건</p>{result.transactions.length === 0 ? <p>조건에 맞는 거래내역이 없습니다.</p> : <table><thead><tr><th>거래일시</th><th>유형</th><th>금액</th><th>상대방</th><th>적요</th></tr></thead><tbody>{result.transactions.map(item => <tr key={item.transactionId}><td>{item.occurredAt}</td><td>{item.direction === 'IN' ? '입금' : '출금'}</td><td>{item.amount}</td><td>{item.counterparty ?? '-'}</td><td>{item.memo ?? '-'}</td></tr>)}</tbody></table>}<div><button type="button" disabled={busy || result.page === 0} onClick={() => search(result.page - 1)}>이전</button><span> {result.page + 1} / {Math.max(result.totalPages, 1)} </span><button type="button" disabled={busy || result.page + 1 >= result.totalPages} onClick={() => search(result.page + 1)}>다음</button></div></>}
    <p>개발용 고객 ID 1과 Fake 계좌 데이터로 연결됩니다. 실제 로그인 principal이나 운영 계좌 데이터는 아직 연결되지 않았습니다.</p></section>
}
