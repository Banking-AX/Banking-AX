import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { toUserMessageOr } from '../../api/errorMessages'
import { getA01Accounts, searchTransactions, taskBffEnabled, type CustomerAccountOption, type TransactionSearchResult } from '../../api/taskApi'
import { transactionDirectionLabel } from '../../ui/customerLabels'
import { useAuth } from '../../auth/AuthContext'
import '../t2/customer-pages.css'

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
  const [rawResult, setResult] = useState<TransactionSearchResult | null>(null)
  // Both result render branches are guarded by its runtime truthiness. Keeping
  // the narrow display alias avoids losing that guard inside pagination callbacks.
  const result = rawResult as TransactionSearchResult
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [accounts, setAccounts] = useState<CustomerAccountOption[]>([])
  const [accountsLoaded, setAccountsLoaded] = useState(false)

  useEffect(() => {
    if (!taskBffEnabled) { setAccountsLoaded(true); return }
    let active = true
    getA01Accounts(`a01-accounts-${crypto.randomUUID()}`).then(response => {
      if (!active) return
      const owned = response.data ?? []; setAccounts(owned); setAccountId(current => current || (owned[0] ? String(owned[0].accountId) : ''))
    }).catch(cause => { if (active) setError(toUserMessageOr(cause, '계좌 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')) }).finally(() => { if (active) setAccountsLoaded(true) })
    return () => { active = false }
  }, [])

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
      setError(toUserMessageOr(cause, '거래내역을 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.'))
    } finally { setBusy(false) }
  }

  if (accountsLoaded && taskBffEnabled) return <main className="t2-page"><header className="t2-hero"><span className="t2-badge">A-01</span><h1>거래내역 조회</h1><p>로그인한 고객이 소유한 계좌만 선택할 수 있습니다.</p></header><section className="t2-card"><div className="t2-grid"><label className="t2-field">계좌<select value={accountId} onChange={e => { setAccountId(e.target.value); setResult(null) }}><option value="">계좌 선택</option>{accounts.map(account => <option key={account.accountId} value={account.accountId}>{account.productName} · {account.maskedAccountNo}</option>)}</select></label><label className="t2-field">시작일<input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} /></label><label className="t2-field">종료일<input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} /></label><label className="t2-field">유형<select value={transactionType} onChange={e => setTransactionType(e.target.value as Direction)}><option value="">전체</option><option value="IN">입금</option><option value="OUT">출금</option></select></label></div>{accounts.length === 0 && <p className="t2-banner">조회 가능한 사용 가능 계좌가 없습니다.</p>}<div className="t2-actions"><button className="t2-button" disabled={busy || !accountId} onClick={() => void search(0)}>{busy ? '조회 중' : '조회'}</button></div>{error && <p className="t2-banner t2-banner--error">{error}</p>}</section>{result && <section className="t2-card"><h2>{result.accountName} · {result.accountNoMasked}</h2>{result.transactions.length === 0 ? <p className="t2-banner">조건에 맞는 거래가 없습니다.</p> : <table className="t2-table"><thead><tr><th>거래일시</th><th>유형</th><th>금액</th><th>상대방</th><th>적요</th></tr></thead><tbody>{result.transactions.map(row => <tr key={row.transactionId}><td>{row.occurredAt}</td><td>{transactionDirectionLabel(row.direction)}</td><td>{row.amount.toLocaleString('ko-KR')}</td><td>{row.counterparty ?? '-'}</td><td>{row.memo ?? '-'}</td></tr>)}</tbody></table>}</section>}</main>

  return <main className="t2-page"><header className="t2-hero"><span className="t2-badge">A-01</span><h1>거래내역 조회</h1><p>계좌·기간·조건을 설정하면 고객 BFF의 실제 조회 결과를 표시합니다.</p></header><section className="t2-card"><div className="t2-grid"><label className="t2-field">계좌 ID<input inputMode="numeric" value={accountId} onChange={e => setAccountId(e.target.value)} /></label><label className="t2-field">시작일<input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} /></label><label className="t2-field">종료일<input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} /></label><label className="t2-field">유형<select value={transactionType} onChange={e => setTransactionType(e.target.value as Direction)}><option value="">전체</option><option value="IN">입금</option><option value="OUT">출금</option></select></label><label className="t2-field">최소 금액<input inputMode="decimal" value={minimumAmount} onChange={e => setMinimumAmount(e.target.value)} /></label><label className="t2-field">최대 금액<input inputMode="decimal" value={maximumAmount} onChange={e => setMaximumAmount(e.target.value)} /></label></div><p className="t2-muted">현재 BFF에는 고객 계좌 선택 목록 및 거래 후 잔액 필드가 없습니다. 임의의 계좌 목록·잔액은 표시하지 않습니다.</p><div className="t2-actions"><button className="t2-button" type="button" disabled={busy || !taskBffEnabled} onClick={() => void search(0)}>{busy ? '조회 중' : '조회'}</button></div>{error && <p className="t2-banner t2-banner--error" role="alert">{error}</p>}</section>{result && <section className="t2-card"><h2>{result.accountName} · {result.accountNoMasked}</h2><p className="t2-muted">{result.timeZone} 기준 · {result.totalElements}건</p>{result.transactions.length === 0 ? <p className="t2-banner">조건에 맞는 거래가 없습니다.</p> : <table className="t2-table"><thead><tr><th>거래일시</th><th>유형</th><th>금액</th><th>상대방</th><th>적요</th></tr></thead><tbody>{result.transactions.map(row => <tr key={row.transactionId}><td>{row.occurredAt}</td><td><span className="t2-badge">{row.direction}</span></td><td>{row.amount.toLocaleString('ko-KR')}</td><td>{row.counterparty ?? '-'}</td><td>{row.memo ?? '-'}</td></tr>)}</tbody></table>}<div className="t2-actions"><button className="t2-button t2-button--secondary" disabled={busy || result.page === 0} onClick={() => void search(result.page - 1)}>이전</button><span>{result.page + 1} / {Math.max(result.totalPages, 1)}</span><button className="t2-button t2-button--secondary" disabled={busy || result.page + 1 >= result.totalPages} onClick={() => void search(result.page + 1)}>다음</button></div></section>}</main>

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
