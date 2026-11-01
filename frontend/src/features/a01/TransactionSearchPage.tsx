import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { searchTransactions, taskBffEnabled, type TransactionSearchResult } from '../../api/taskApi'
import { useAuth } from '../../auth/AuthContext'

type Direction = '' | 'IN' | 'OUT'
type SortOrder = 'RECENT' | 'OLDEST'

/** Displays only the BFF -> Customer Agent -> Spring result. No local ledger data is invented. */
export function TransactionSearchPage() {
  const { workItemId } = useParams()
  const { user } = useAuth()
  const [accountId, setAccountId] = useState('')
  const [startDate, setStartDate] = useState('2026-10-01')
  const [endDate, setEndDate] = useState('2026-10-31')
  const [transactionType, setTransactionType] = useState<Direction>('')
  const [minimumAmount, setMinimumAmount] = useState('')
  const [maximumAmount, setMaximumAmount] = useState('')
  const [keyword, setKeyword] = useState('')
  const [sortOrder, setSortOrder] = useState<SortOrder>('RECENT')
  const [result, setResult] = useState<TransactionSearchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const search = async (page = 0) => {
    const item = Number(workItemId); const account = Number(accountId)
    if (!Number.isSafeInteger(item) || item <= 0 || !Number.isSafeInteger(account) || account <= 0 || !startDate || !endDate) {
      setError('업무 ID, 계좌 및 조회 기간을 확인해 주세요.'); return
    }
    setBusy(true); setError(null)
    try {
      const response = await searchTransactions({ requestId: `a01-ui-${item}-${page}`, workItemId: item, accountId: account, startDate, endDate, transactionType: transactionType || null, minimumAmount: minimumAmount || null, maximumAmount: maximumAmount || null, keyword: keyword || null, sortOrder, page, size: 20 })
      if (!response.data) throw new Error('거래 조회 결과가 비어 있습니다.')
      setResult(response.data)
    } catch (cause) {
      setError(cause instanceof ApiError || cause instanceof Error ? cause.message : '거래내역을 조회할 수 없습니다.')
    } finally { setBusy(false) }
  }

  return <section>
    <h1>거래내역 조회</h1>
    <p>업무 ID: {workItemId ?? '미지정'} · 로그인 사용자: {user?.name ?? '없음'}</p>
    {!taskBffEnabled && <p role="status">로그인 BFF 연결 대기 중입니다. 이 화면은 임의 고객 ID나 내부 토큰으로 조회하지 않습니다.</p>}
    <label>계좌 ID <input inputMode="numeric" value={accountId} onChange={e => setAccountId(e.target.value)} /></label>
    <label>시작일 <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} /></label>
    <label>종료일 <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} /></label>
    <label>유형 <select value={transactionType} onChange={e => setTransactionType(e.target.value as Direction)}><option value="">전체</option><option value="IN">입금</option><option value="OUT">출금</option></select></label>
    <label>최소 금액 <input inputMode="decimal" value={minimumAmount} onChange={e => setMinimumAmount(e.target.value)} /></label>
    <label>최대 금액 <input inputMode="decimal" value={maximumAmount} onChange={e => setMaximumAmount(e.target.value)} /></label>
    <label>검색어 <input value={keyword} onChange={e => setKeyword(e.target.value)} /></label>
    <label>정렬 <select value={sortOrder} onChange={e => setSortOrder(e.target.value as SortOrder)}><option value="RECENT">최신순</option><option value="OLDEST">과거순</option></select></label>
    <button type="button" disabled={busy || !taskBffEnabled} onClick={() => search(0)}>{busy ? '조회 중' : '조회'}</button>
    {error && <p role="alert">{error}</p>}
    {result && <><h2>{result.accountName} ({result.accountNoMasked})</h2><p>{result.timeZone} 기준 · {result.totalElements}건</p>
      {result.transactions.length === 0 ? <p>조건에 맞는 거래내역이 없습니다.</p> : <table><thead><tr><th>거래일시</th><th>유형</th><th>금액</th><th>상대방</th><th>적요</th></tr></thead><tbody>{result.transactions.map(row => <tr key={row.transactionId}><td>{row.occurredAt}</td><td>{row.direction === 'IN' ? '입금' : '출금'}</td><td>{row.amount}</td><td>{row.counterparty ?? '-'}</td><td>{row.memo ?? '-'}</td></tr>)}</tbody></table>}
      <button type="button" disabled={busy || result.page === 0 || !taskBffEnabled} onClick={() => search(result.page - 1)}>이전</button><span> {result.page + 1} / {Math.max(result.totalPages, 1)} </span><button type="button" disabled={busy || result.page + 1 >= result.totalPages || !taskBffEnabled} onClick={() => search(result.page + 1)}>다음</button>
    </>}
  </section>
}
