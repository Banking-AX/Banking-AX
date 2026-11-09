import { useCallback, useEffect, useState } from 'react'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, taskBffEnabled, type C02Clarification, type C02Statement } from '../../api/taskApi'
import { formatDateTime, type DocumentRow } from './c02CaseView'
import './c02-qa.css'

/*
 * STAFF-21 (staff question / customer answer) and HIST-11 (customer-written statement).
 * Everything shown here is what a person typed: the staff question and the customer's own words.
 * Nothing is summarised or generated, and an unanswered visit question is only a provisional absence.
 */

const KIND_LABEL: Record<C02Clarification['kind'], string> = { VISIT_ATTENDANCE: '방문 여부', DOCUMENT: '서류', OTHER: '기타' }
const STATUS_LABEL: Record<C02Clarification['status'], string> = { OPEN: '답변 대기', ANSWERED: '답변 완료', EXPIRED: '기한 지남', ESCALATED: '직원 확인' }
const rid = (action: string, caseId: number) => `c02-qa-${action}-${caseId}-${crypto.randomUUID()}`

function useClarifications(caseId: number, side: 'staff' | 'customer') {
  const [rows, setRows] = useState<C02Clarification[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(async () => {
    if (!taskBffEnabled) { setRows([]); return }
    try {
      const response = side === 'staff' ? await c02Api.staffClarifications(caseId, rid('list', caseId)) : await c02Api.customerClarifications(caseId, rid('list', caseId))
      setRows(response.data ?? []); setError(null)
    } catch (cause) { setRows([]); setError(toUserMessageOr(cause, '추가 확인 내역을 불러오지 못했어요.')) }
  }, [caseId, side])
  useEffect(() => { void load() }, [load])
  return { rows, error, setError, load }
}

function useStatements(caseId: number, side: 'staff' | 'customer') {
  const [rows, setRows] = useState<C02Statement[] | null>(null)
  const load = useCallback(async () => {
    if (!taskBffEnabled) { setRows([]); return }
    try {
      const response = side === 'staff' ? await c02Api.staffStatements(caseId, rid('statements', caseId)) : await c02Api.customerStatements(caseId, rid('statements', caseId))
      setRows(response.data ?? [])
    } catch { setRows([]) }
  }, [caseId, side])
  useEffect(() => { void load() }, [load])
  return { rows, load }
}

function QuestionMeta({ row }: { row: C02Clarification }) {
  return <span className="c02-qa__meta">{KIND_LABEL[row.kind]} · {formatDateTime(row.askedAt)}{row.askedByName ? ` · ${row.askedByName}` : ''}{row.status === 'OPEN' && row.dueAt ? ` · ${formatDateTime(row.dueAt)}까지` : ''}</span>
}

function Answer({ row }: { row: C02Clarification }) {
  if (row.status === 'ANSWERED') return <div className="c02-qa__answer"><span>고객 답변 원문 · {formatDateTime(row.answeredAt)}</span><p>{row.answer}</p></div>
  if (row.status === 'EXPIRED') return <p className="c02-qa__expired">기한 안에 답변이 없었어요.{row.nonresponseAssumption === 'UNCONFIRMED_ABSENT' ? ' 방문 여부는 잠정 불참(UNCONFIRMED_ABSENT)으로만 표시되며, 확정된 불참이 아니에요.' : ''}</p>
  return null
}

/** Staff side: ask before the review starts, read answers and statements as written. */
export function C02StaffCaseQa({ caseId, canAsk, onChanged }: { caseId: number; canAsk: boolean; onChanged: () => void }) {
  const questions = useClarifications(caseId, 'staff')
  const statements = useStatements(caseId, 'staff')
  const [kind, setKind] = useState<C02Clarification['kind']>('VISIT_ATTENDANCE')
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const ask = async () => {
    setBusy(true)
    try {
      await c02Api.askClarification(caseId, { kind, question: question.trim(), requestId: rid('ask', caseId) })
      setQuestion(''); await questions.load(); onChanged()
    } catch (cause) { questions.setError(toUserMessageOr(cause, '질문을 보내지 못했어요.')) }
    finally { setBusy(false) }
  }
  const valid = question.trim().length >= 5 && question.trim().length <= 500
  return <div className="v17-sd__pair">
    <section className="v17-card c02-qa" aria-label="고객 추가 확인">
      <h2 className="v17-h2">고객 추가 확인 <span className="v17-muted-s">직원 질문 · 고객 답변 원문</span></h2>
      {questions.error && <p className="c02-alert" role="alert">{questions.error}</p>}
      {questions.rows === null ? <span className="v17-muted">불러오고 있어요.</span>
        : questions.rows.length === 0 ? <span className="v17-muted">보낸 질문이 없어요.</span>
          : <ul className="c02-qa__list">{questions.rows.map(row => <li key={row.id} className={`is-${row.status.toLowerCase()}`}>
            <div className="c02-qa__head"><strong>{row.question}</strong><span className={`c02-qa__pill is-${row.status.toLowerCase()}`}>{STATUS_LABEL[row.status]}</span></div>
            <QuestionMeta row={row} /><Answer row={row} />
            {row.resultRevision !== null && <span className="c02-qa__meta">답변 반영 · 접수 버전 r{row.resultRevision}에서 사전점검 다시 실행</span>}
          </li>)}</ul>}
      {canAsk ? <div className="c02-qa__form">
        <label>질문 종류<select value={kind} onChange={event => setKind(event.target.value as C02Clarification['kind'])}>
          {Object.entries(KIND_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label>질문 (사실 확인 1건)<textarea value={question} maxLength={500} rows={2} onChange={event => setQuestion(event.target.value)} placeholder="예: 둘째 상속인께서 영업점에 직접 방문하실 수 있나요?" /></label>
        <div className="c02-qa__actions"><span className="v17-muted-s">고객에게 웹 알림으로 전달되고, 72시간 안에 답이 없으면 기한 지남으로 닫혀요.</span>
          <button type="button" className="v17-btn v17-btn--primary" disabled={!valid || busy} onClick={() => void ask()}>{busy ? '보내는 중…' : '질문 보내기'}</button></div>
      </div> : <span className="v17-muted-s">질문은 검토 시작 전에만 보낼 수 있어요. 검토 중 확인이 필요한 내용은 검토 결과에 남겨 주세요.</span>}
    </section>
    <section className="v17-card c02-qa" aria-label="고객 작성 진술">
      <h2 className="v17-h2">고객 작성 진술 <span className="v17-muted-s">고객이 직접 쓴 원문</span></h2>
      {statements.rows === null ? <span className="v17-muted">불러오고 있어요.</span>
        : statements.rows.length === 0 ? <span className="v17-muted">고객이 작성한 진술이 없어요.</span>
          : <ul className="c02-qa__list">{statements.rows.map(row => <li key={row.id}><p className="c02-qa__text">{row.text}</p><span className="c02-qa__meta">{formatDateTime(row.writtenAt)} 작성 · r{row.revision} · {formatDateTime(row.expiresAt)} 삭제 예정</span></li>)}</ul>}
      <span className="v17-muted-s">진술은 작성 후 30일 뒤 삭제돼요. 고객 주장일 뿐 확인된 사실이 아니며, AI 검토 의견에는 건수만 전달돼요.</span>
    </section>
  </div>
}

/** Customer side: answer open questions and add a statement while the Case can still change. */
export function C02CustomerCaseQa({ caseId, caseStatus, onChanged }: { caseId: number; caseStatus: string; onChanged: () => void }) {
  const questions = useClarifications(caseId, 'customer')
  const statements = useStatements(caseId, 'customer')
  const [drafts, setDrafts] = useState<Record<number, string>>({})
  const [statement, setStatement] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const writable = caseStatus === 'DRAFT' || caseStatus === 'SUBMITTED'
  const answer = async (row: C02Clarification) => {
    setBusy(true); setError(null)
    try {
      await c02Api.answerClarification(caseId, row.id, { answer: (drafts[row.id] ?? '').trim(), requestId: rid('answer', caseId) })
      setDrafts(current => ({ ...current, [row.id]: '' })); await questions.load(); onChanged()
    } catch (cause) { setError(toUserMessageOr(cause, '답변을 보내지 못했어요. 잠시 후 다시 시도해 주세요.')); await questions.load() }
    finally { setBusy(false) }
  }
  const write = async () => {
    setBusy(true); setError(null)
    try { await c02Api.writeStatement(caseId, { text: statement.trim(), requestId: rid('statement', caseId) }); setStatement(''); await statements.load() }
    catch (cause) { setError(toUserMessageOr(cause, '내용을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    finally { setBusy(false) }
  }
  const rows = questions.rows ?? []
  if (rows.length === 0 && !writable && (statements.rows ?? []).length === 0) return null
  const open = rows.filter(row => row.status === 'OPEN').length
  return <section className="v17-vd__card c02-qa" aria-label="추가 확인과 전달할 내용">
    <div className="v17-vd__visit-title"><h2>직원 추가 확인</h2>{open > 0 && <span className="v17-vd__pill-s v17-vd__pill-s--warn">답변 필요 {open}건</span>}</div>
    {(error ?? questions.error) && <p className="c02-alert" role="alert">{error ?? questions.error}</p>}
    {rows.length === 0 ? <p className="v17-vd__text">직원이 보낸 질문이 없어요. 확인이 필요하면 이곳과 웹 알림으로 안내해 드려요.</p>
      : <ul className="c02-qa__list">{rows.map(row => <li key={row.id} className={`is-${row.status.toLowerCase()}`}>
        <div className="c02-qa__head"><strong>{row.question}</strong><span className={`c02-qa__pill is-${row.status.toLowerCase()}`}>{STATUS_LABEL[row.status]}</span></div>
        <QuestionMeta row={row} /><Answer row={row} />
        {row.status === 'OPEN' && <div className="c02-qa__form">
          <label>답변<textarea rows={2} maxLength={1000} value={drafts[row.id] ?? ''} onChange={event => setDrafts(current => ({ ...current, [row.id]: event.target.value }))} placeholder="알고 계신 사실대로 적어 주세요." /></label>
          {row.kind === 'DOCUMENT' && <span className="c02-qa__meta">파일을 바꿔야 하면 아래 ‘서류 보완·교체’에서 먼저 올린 뒤 답변해 주세요.</span>}
          <div className="c02-qa__actions"><span className="c02-qa__meta">적어 주신 그대로 담당 직원에게 전달돼요. 답변하면 같은 접수 건에서 점검을 다시 해요.</span>
            <button type="button" className="v17-vd__btn v17-vd__btn--primary" disabled={busy || !(drafts[row.id] ?? '').trim()} onClick={() => void answer(row)}>답변 보내기</button></div>
        </div>}
      </li>)}</ul>}
    <div className="c02-qa__divider" />
    <h3 className="c02-qa__h3">직원에게 전달할 내용 <span className="c02-qa__meta">선택 · 30일 뒤 삭제</span></h3>
    {(statements.rows ?? []).length > 0 && <ul className="c02-qa__list">{(statements.rows ?? []).map(row => <li key={row.id}><p className="c02-qa__text">{row.text}</p><span className="c02-qa__meta">{formatDateTime(row.writtenAt)} 작성 · {formatDateTime(row.expiresAt)} 삭제 예정</span></li>)}</ul>}
    {writable ? <div className="c02-qa__form">
      <label>상황 설명<textarea rows={2} maxLength={1000} value={statement} onChange={event => setStatement(event.target.value)} placeholder="예: 둘째 상속인은 해외에 있어 방문이 어려워요." /></label>
      <div className="c02-qa__actions"><span className="c02-qa__meta">직원 검토가 시작되기 전까지 남길 수 있어요. 작성한 내용은 확인된 사실로 처리되지 않아요.</span>
        <button type="button" className="v17-vd__btn v17-vd__btn--line" disabled={busy || !statement.trim()} onClick={() => void write()}>내용 남기기</button></div>
    </div> : (statements.rows ?? []).length === 0 && <p className="v17-vd__text">남긴 내용이 없어요.</p>}
  </section>
}

/**
 * DOC-20: until staff start the review the customer can replace a file on the same Case. Each file makes a new revision
 * and the precheck runs on it; earlier files and check runs stay. After the review starts the server refuses.
 */
export function C02SupplementCard({ caseId, revision, rows, onChanged }: { caseId: number; revision: number; rows: DocumentRow[]; onChanged: () => Promise<void> | void }) {
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const files = rows.filter(row => row.slot.documentTypeCode !== 'DELEGATION_RECEIPT_NO')
  if (files.length === 0) return null
  const replace = async (row: DocumentRow, file?: File) => {
    if (!file || busyKey) return
    setBusyKey(row.key); setError(null); setDone(null)
    try {
      await c02Api.uploadDocument(caseId, { revision, requestId: `c02-supplement-${caseId}-${crypto.randomUUID()}`, documentSlotKey: row.key, file })
      setDone(`${row.title} 파일을 받았어요. 같은 접수 건에서 사전점검을 다시 했어요. 담당 직원의 확인 결과는 아니에요.`)
    } catch (cause) { setError(toUserMessageOr(cause, '파일을 올리지 못했어요. 직원 검토가 이미 시작됐다면 더 바꿀 수 없어요.')) }
    finally { setBusyKey(null); await onChanged() }
  }
  return <section className="v17-vd__card c02-qa" aria-label="서류 보완·교체">
    <details className="c02-qa__details">
      <summary><h2>서류 보완·교체</h2><span className="c02-qa__meta">직원 검토가 시작되기 전까지 바꿀 수 있어요</span></summary>
      {error && <p className="c02-alert" role="alert">{error}</p>}
      {done && <p className="c02-qa__done" role="status">{done}</p>}
      <ul className="c02-qa__files">{files.map(row => <li key={row.key}>
        <span><strong>{row.title}{row.owner ? ` · ${row.owner}` : ''}</strong><span className="c02-qa__meta">{row.uploaded ? `${row.uploaded.fileName} · v${row.uploaded.version}` : '아직 올린 파일이 없어요'}</span></span>
        <label className={`c02-file-button${busyKey ? ' is-disabled' : ''}`}>{busyKey === row.key ? '올리는 중…' : row.uploaded ? '파일 교체' : '파일 선택'}
          <input type="file" hidden accept=".pdf,.jpg,.jpeg,.png" disabled={Boolean(busyKey)} aria-label={`${row.title} 파일 ${row.uploaded ? '교체' : '선택'}`} onChange={event => { void replace(row, event.currentTarget.files?.[0]); event.currentTarget.value = '' }} /></label>
      </li>)}</ul>
      <span className="c02-qa__meta">이전 파일과 점검 기록은 지우지 않고 남겨요. 검토가 시작된 뒤 부족한 서류는 담당 직원의 결과 안내에 따라 새로 신청해 주세요.</span>
    </details>
  </section>
}
