import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type Branch, type C02CustomerCaseSnapshot, type C02Document, type Reservation } from '../api/taskApi'
import { caseNumber, casePill, formatDateTime, groupDocuments, heirCaption, lastActivityAt, timelineSteps, uploadProgress, visitConfirmation, visitLabel, type StatusPill, reservationNumber } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'
import { C02CustomerCaseQa, C02SupplementCard } from '../features/c02/C02CaseQa'

const RESULT_COPY = {
  PROCEED: { title: '직원 검토 결과 · 진행 가능', body: '방문 전 서류 확인을 마쳤어요. 이제 방문할 영업점과 시간을 예약할 수 있어요.', tone: 'success' },
  REQUIREMENT_FAILED: { title: '직원 검토 결과 · 요건 미충족', body: '신청 요건을 충족하지 않아 이번 접수는 종료됐어요. 자세한 내용은 담당 직원 안내를 확인해 주세요.', tone: 'danger' },
  DOCUMENT_INSUFFICIENT: { title: '직원 검토 결과 · 서류 부족', body: '제출 서류로 확인이 어려워 이번 접수는 종료됐어요. 서류를 보완해 새로 신청할 수 있어요.', tone: 'danger' },
} as const
const RESERVATION_LABEL: Record<string, StatusPill> = {
  SELECTED: { label: '예약 · 선택됨', tone: 'info' }, PENDING_ASSIGNMENT: { label: '예약 · 담당자 배정 중', tone: 'info' },
  CONFIRMED: { label: '예약 확정', tone: 'success' }, NEEDS_MANUAL_ASSIGNMENT: { label: '예약 · 수동 배정 대기', tone: 'warn' },
}

const LOCK_MS = 24 * 60 * 60 * 1000
const InfoIcon = () => <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M12 11v5M12 8h.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>

/** Customer C-02 detail: intake/review status, staff result and the reservation entry point stay separate. */
export function C02CaseDetailPage() {
  const { caseId } = useParams(); const parsed = Number(caseId)
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [snapshot, setSnapshot] = useState<C02CustomerCaseSnapshot | null>(null)
  const [reservation, setReservation] = useState<Reservation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [branches, setBranches] = useState<Branch[]>([])
  const [askCancelVisit, setAskCancelVisit] = useState(false)
  const [expiredReservation, setExpiredReservation] = useState<Reservation | null>(null)
  const valid = Number.isSafeInteger(parsed) && parsed > 0
  const load = useCallback(async () => {
    if (!taskBffEnabled || !valid) return
    try {
      const response = await c02Api.snapshot(parsed, `c02-detail-${Date.now()}`)
      setSnapshot(response.data); setError(null)
      if (response.data?.caseStatus === 'REVIEW_PASSED') {
        const reservations = await c02Api.reservations(`c02-detail-reservations-${Date.now()}`).catch(() => null)
        setReservation(reservations?.data?.find(item => item.caseId === parsed && item.status !== 'CANCELED' && item.status !== 'EXPIRED') ?? null)
        // NOTI-23: an unassigned booking that reached its time closes as EXPIRED; the Case can be booked again.
        setExpiredReservation(reservations?.data?.filter(item => item.caseId === parsed && item.status === 'EXPIRED').sort((a, b) => b.id - a.id)[0] ?? null)
        const directory = await c02Api.branches(`c02-detail-branches-${Date.now()}`).catch(() => null)
        setBranches(directory?.data ?? [])
      }
    } catch (cause) { setError(toUserMessageOr(cause, '접수 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) }
  }, [parsed, valid])
  useEffect(() => { void load() }, [load])
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [busy, setBusy] = useState(false)
  // The server decides: if staff already started the review, the cancel is refused and the page reloads.
  const cancelCase = async () => {
    setBusy(true)
    try { await c02Api.cancelCase(parsed, `c02-cancel-${parsed}-${crypto.randomUUID()}`); setError(null) }
    catch (cause) { setError(toUserMessageOr(cause, '접수를 취소하지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    finally { setBusy(false); setConfirmCancel(false); await load() }
  }

  // Same rule as the reservation page: the server refuses changes within 24 hours of the visit.
  const cancelVisit = async () => {
    if (!reservation) return
    setBusy(true)
    try { await c02Api.cancelReservation(reservation.id, `reservation-cancel-${reservation.id}-${crypto.randomUUID()}`); setError(null) }
    catch (cause) { setError(toUserMessageOr(cause, '예약을 취소하지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    finally { setBusy(false); setAskCancelVisit(false); setReservation(null); await load() }
  }

  // DOC-09: lets the customer see for themselves that the right file went up.
  const download = async (file: C02Document) => {
    try {
      const url = URL.createObjectURL(await c02Api.customerDocument(parsed, file.documentFileId))
      const link = document.createElement('a'); link.href = url; link.download = file.fileName; link.click()
      setTimeout(() => URL.revokeObjectURL(url), 60000)
    } catch (cause) { setError(toUserMessageOr(cause, '파일을 내려받지 못했어요. 잠시 후 다시 시도해 주세요.')) }
  }

  if (!valid) return <main className="c02-detail"><p className="c02-alert" role="alert">접수 번호가 올바르지 않아요.</p></main>
  if (!snapshot) return <main className="c02-detail">{error ? <p className="c02-alert" role="alert">{error}</p> : <p className="c02-notice" role="status">접수 정보를 불러오고 있어요.</p>}</main>

  const intake = snapshot.intake
  const heirs = intake?.heirs ?? []
  const groups = groupDocuments(snapshot.requiredDocuments, snapshot.documents, intake)
  const rows = [...groups.common, ...groups.byHeir.flatMap(group => group.rows)]
  const progress = uploadProgress(groups)
  const decision = snapshot.finalDecision
  const result = decision ? RESULT_COPY[decision.outcome] : null
  const pills: StatusPill[] = [casePill(snapshot.caseStatus, snapshot.reviewStatus)]
  if (snapshot.caseStatus === 'REVIEW_PASSED') pills.push(reservation ? RESERVATION_LABEL[reservation.status] ?? { label: '예약 상태 확인', tone: 'info' } : { label: '예약 · 예약 전', tone: 'info' })
  const number = caseNumber(snapshot.caseId, snapshot.timeline?.createdAt)
  const showModal = params.get('submitted') === '1' && snapshot.caseStatus !== 'DRAFT'

  const visitState = snapshot.caseStatus !== 'REVIEW_PASSED' ? (decision ? 'closed' : 'wait')
    : !reservation ? 'ok' : reservation.status === 'NEEDS_MANUAL_ASSIGNMENT' ? 'manual'
      : new Date(reservation.startsAt).getTime() - Date.now() <= LOCK_MS ? 'locked' : reservation.status === 'CONFIRMED' ? 'confirmed' : 'pending'
  const visitPill = visitState === 'wait' ? { label: '결과 대기', tone: 'muted' } : visitState === 'closed' ? { label: '예약 대상 아님', tone: 'muted' } : visitState === 'ok' ? { label: '예약 전', tone: 'info' }
    : visitState === 'manual' ? { label: '수동 배정 대기', tone: 'warn' } : visitState === 'pending' ? { label: '배정 중', tone: 'info' } : { label: '예약 확정', tone: 'success' }
  const branchName = reservation ? branches.find(item => item.id === reservation.branchId)?.name ?? `지점 #${reservation.branchId}` : ''
  const changeUntil = reservation ? new Date(new Date(reservation.startsAt).getTime() - LOCK_MS).toISOString() : null
  const timeline = timelineSteps(snapshot, snapshot.timeline)

  return <main className="v17-vd">
    <nav className="v17-vd__crumb" aria-label="현재 위치"><Link to="/">홈</Link><span>›</span><Link to="/work">업무 요청 기록</Link><span>›</span><span className="is-current">상세</span></nav>
    {error && <p className="c02-alert" role="alert">{error}</p>}
    <section className="v17-vd__card v17-vd__head">
      <div className="v17-vd__titlebar"><div><span className="v17-vd__tag">C · 직원 검토</span><h1>상속예금 해지 및 지급 사전접수</h1></div>
        <div className="v17-vd__pills">{pills.map(pill => <span key={pill.label} className={`v17-vd__pill v17-vd__pill--${pill.tone}`}>{pill.label}</span>)}</div></div>
      <dl className="v17-vd__meta"><div><dt>접수번호</dt><dd className="is-number">{number}</dd></div><div><dt>접수 일시</dt><dd>{formatDateTime(snapshot.timeline?.submittedAt)}</dd></div><div><dt>최근 변경</dt><dd>{formatDateTime(lastActivityAt(snapshot))}</dd></div></dl>
      {snapshot.caseStatus === 'DRAFT' && <div className="v17-vd__actions"><Link className="v17-vd__btn v17-vd__btn--primary" to={`/tasks/c02/${snapshot.workItemId}?edit=1`}>신청정보 수정</Link></div>}
      {snapshot.caseStatus === 'SUBMITTED' && <div className="v17-vd__actions">
        {confirmCancel
          ? <div className="v17-vd__confirm"><span>접수를 취소할까요? 취소한 접수는 되돌릴 수 없고, 다시 신청하면 새 접수로 진행돼요.</span><button type="button" className="is-keep" disabled={busy} onClick={() => setConfirmCancel(false)}>돌아가기</button><button type="button" className="is-do" disabled={busy} onClick={() => void cancelCase()}>취소하기</button></div>
          : <><button type="button" className="v17-vd__btn v17-vd__btn--danger" onClick={() => setConfirmCancel(true)}>접수 취소</button><span>직원이 검토를 시작하기 전까지만 취소할 수 있어요.</span></>}
      </div>}
    </section>

    <C02CustomerCaseQa caseId={snapshot.caseId} caseStatus={snapshot.caseStatus} onChanged={() => void load()} />
    {snapshot.caseStatus === 'SUBMITTED' && <C02SupplementCard caseId={snapshot.caseId} revision={snapshot.revision} rows={rows} onChanged={load} />}

    {decision && result && <section className={`v17-vd__result v17-vd__result--${result.tone}`}>
      <header><span aria-hidden="true">{result.tone === 'success' ? <svg width="22" height="22" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" /></svg> : '!'}</span><div><h2>{result.title}</h2><span>{result.body}</span></div></header>
      <div className="v17-vd__result-body">
        <div className="v17-vd__quote"><span>담당 직원 안내 (원문)</span><p>{decision.customerMessage}</p><span>담당 직원 확정 · {formatDateTime(snapshot.timeline?.resultNotifiedAt ?? snapshot.timeline?.decidedAt)} · 웹 알림 발송</span></div>
        {decision.outcome === 'PROCEED' && <div className="v17-vd__bring"><strong>방문할 때 가져올 것</strong>
          <ul>{groups.visitOnly.map(row => <li key={row.key}><span>{row.title}</span><span className="is-need">방문 시 필수</span></li>)}<li><span>업로드한 서류의 원본 (3개월 이내 발급)</span><span className="is-need">방문 시 필수</span></li>{heirs.filter(heir => heir.visitStatus === 'UNCONFIRMED_ABSENT').map(heir => <li key={heir.personRef} className="is-conditional"><span>{heir.name}님이 오지 못하는 경우: 신분증 사본, 인감증명서(본인발급용·3개월 이내), 인감 날인 위임장 — 또는 SolBank 앱 위임장 접수번호</span><span className="is-cond">불참 시</span></li>)}</ul></div>}
        <p className="v17-vd__info"><InfoIcon />{decision.outcome === 'PROCEED' ? '‘진행 가능’과 방문 예약은 예금 지급이 확정되었다는 뜻이 아니에요. 실제 지급 여부는 방문했을 때 영업점에서 원본을 확인한 뒤 정해져요.' : '이 결과는 모의 접수 검토 결과이며 법정 통지·최종 결정이 아니에요. 다시 신청하면 새 접수로 진행돼요.'}</p>
      </div>
    </section>}

    <section className={`v17-vd__card v17-vd__visit is-${visitState}`}>
      <div className="v17-vd__visit-title"><h2>방문 예약</h2><span className={`v17-vd__pill-s v17-vd__pill-s--${visitPill.tone}`}>{visitPill.label}</span></div>
      {visitState === 'wait' && <p className="v17-vd__text">직원 검토 결과를 기다리고 있어요. 결과가 ‘진행 가능’이면 알림을 받은 뒤 방문할 영업점과 시간을 예약할 수 있어요. 지금은 예약 정보가 없어요.</p>}
      {visitState === 'closed' && <p className="v17-vd__text">이 접수는 방문 예약 대상이 아니에요.</p>}
      {visitState === 'ok' && expiredReservation && <p className="v17-vd__note is-manual">{formatDateTime(expiredReservation.startsAt)} 방문 예약은 담당 직원이 배정되지 않은 채 시간이 지나 만료됐어요. 예약 확정이 아니었고, 다시 예약할 수 있어요.</p>}
      {visitState === 'ok' && <div className="v17-vd__row"><p>아직 예약하지 않았어요. 가까운 영업점과 방문 시간을 골라 주세요. 예약하면 그 지점의 담당 직원이 배정돼요.</p><button type="button" className="v17-vd__btn v17-vd__btn--primary v17-vd__btn--lg" onClick={() => navigate(`/tasks/c02/reservations?caseId=${snapshot.caseId}`)}>방문 영업점 예약</button></div>}
      {reservation && visitState !== 'ok' && <div className="v17-vd__booked">
        <dl className="v17-vd__meta"><div><dt>예약번호</dt><dd className="is-strong">{reservationNumber(reservation.id)}</dd></div><div><dt>영업점</dt><dd className="is-strong">{branchName}</dd></div><div><dt>방문 일시</dt><dd className="is-strong">{formatDateTime(reservation.startsAt)}</dd></div><div><dt>변경·취소 가능</dt><dd>{formatDateTime(changeUntil)}까지</dd></div></dl>
        <p className={`v17-vd__note is-${visitState}`}>{visitState === 'manual' ? '자동 배정이 되지 않아 영업점이 담당 직원을 직접 배정하고 있어요. 배정 전에는 예약 확정이 아니에요.' : visitState === 'pending' ? '담당 직원 배정을 기다리고 있어요. 배정 전에는 예약 확정이 아니에요.' : visitState === 'locked' ? '방문 24시간 이내라 변경·취소할 수 없어요. 일정이 어려우면 영업점에 문의해 주세요.' : '예약이 확정되었어요. 담당 직원이 방문 전에 접수 내용과 서류를 미리 확인해요.'}</p>
        {visitState === 'locked'
          ? <div className="v17-vd__actions"><button type="button" className="v17-vd__btn v17-vd__btn--off" disabled>예약 변경</button><button type="button" className="v17-vd__btn v17-vd__btn--off" disabled>예약 취소</button><span className="v17-vd__locked">방문 24시간 이내라 변경·취소할 수 없어요.</span></div>
          : askCancelVisit
            ? <div className="v17-vd__confirm"><span>예약을 취소할까요? 잡혀 있던 시간과 담당 직원 배정이 풀려요. 다시 예약할 수 있어요.</span><button type="button" className="is-keep" disabled={busy} onClick={() => setAskCancelVisit(false)}>돌아가기</button><button type="button" className="is-do" disabled={busy} onClick={() => void cancelVisit()}>예약 취소</button></div>
            : <div className="v17-vd__actions"><button type="button" className="v17-vd__btn v17-vd__btn--line" onClick={() => navigate(`/tasks/c02/reservations?caseId=${snapshot.caseId}&change=1`)}>예약 변경</button><button type="button" className="v17-vd__btn v17-vd__btn--danger" onClick={() => setAskCancelVisit(true)}>예약 취소</button></div>}
      </div>}
    </section>

    <div className="v17-vd__grid">
      <section className="v17-vd__card v17-vd__timeline"><h2>진행 타임라인</h2><p>접수·검토와 방문 예약은 따로 표시돼요</p>
        <ol>{timeline.map((step, index) => <li key={step.label} className={`is-${step.state}`}><div><span className="v17-vd__dot" /><span className={`v17-vd__line${index === timeline.length - 1 ? ' is-last' : ''}`} /></div><div><strong>{step.label}</strong><span>{step.at ? formatDateTime(step.at) : step.state === 'pending' ? '대기' : step.state === 'current' ? '진행 중' : '—'}{step.note ? ` · ${step.note}` : ''}</span></div></li>)}
          {reservation && <li className="is-visit"><div><span className="v17-vd__dot" /><span className="v17-vd__line is-last" /></div><div><strong>{reservation.status === 'CONFIRMED' ? '담당 직원 배정 · 예약 확정' : '예약 요청 · 담당 직원 배정 중'}</strong><span>{formatDateTime(reservation.startsAt)} 방문</span></div></li>}</ol>
      </section>
      <div className="v17-vd__stack">
        <section className="v17-vd__card"><h2>상속인별 방문 계획</h2>
          <div className="v17-vd__scroll"><table className="v17-vd__table"><thead><tr><th>상속인</th><th>입력한 방문 방식</th><th>확인 상태</th></tr></thead><tbody>{heirs.map(heir => <tr key={heir.personRef}><td>{heir.name} ({heirCaption(heir)})</td><td>{visitLabel(heir)}</td><td>{heir.visitStatus === 'UNCONFIRMED_ABSENT' ? <span className="v17-vd__warnchip">{visitConfirmation(heir)}</span> : visitConfirmation(heir)}</td></tr>)}</tbody></table></div>
          {heirs.some(heir => heir.visitStatus === 'UNCONFIRMED_ABSENT') && <p className="v17-vd__small">방문 여부 확인 질문에 답이 없어, 준비서류를 안내하기 위해 <b>잠정 불참</b>으로 표시했어요. 확정된 불참이 아니며, 최종 확인은 직원이 해요.</p>}
        </section>
        <section className="v17-vd__card"><div className="v17-vd__progress-title"><h2>접수 진행 현황</h2><span>필수정보 <b>입력 완료</b></span><span>업로드 서류 <b>{rows.filter(row => row.uploaded).length}/{rows.length}</b></span>{progress.missingRequired > 0 && <span className="is-warn">필수 {progress.missingRequired}건 미제출</span>}</div>
          <div className="v17-vd__scroll"><table className="v17-vd__table v17-vd__table--docs"><thead><tr><th>서류 · 제출자</th><th>제출됨</th><th>시스템 확인</th><th>직원 확인</th></tr></thead><tbody>{rows.map(row => <tr key={row.key}><td>{row.title}{row.uploaded && <button type="button" className="c02-qa__open" onClick={() => void download(row.uploaded!)}>내 파일 받기</button>}</td><td className="is-center">{row.uploaded ? <b className="is-ok">✓</b> : '—'}</td><td className="is-small">{row.uploaded?.status === 'SYSTEM_VALIDATED' ? '형식 확인' : '해당 없음 · 원문 확인'}</td><td className="is-small">{row.uploaded?.status === 'HUMAN_VERIFIED' ? <b className="is-ok">확인 완료</b> : decision ? '검토 결과 참고' : '대기'}</td></tr>)}</tbody></table></div>
          <p className="v17-vd__small">일반 증명서는 내용을 자동으로 읽지 않아 시스템 확인 대상이 아니에요. 직원이 원문을 확인해요.</p>
        </section>
      </div>
    </div>

    <p className="v17-vd__extra">업무 번호 #{snapshot.workItemId} · <Link to="/tasks/c02">상속예금 접수 내역</Link></p>

    {showModal && <SubmittedModal number={number} submittedAt={snapshot.timeline?.submittedAt ?? null} heirCount={heirs.length} documentCount={rows.filter(row => row.uploaded).length} pill={pills[0]}
      onClose={() => { params.delete('submitted'); setParams(params, { replace: true }) }} onChat={() => navigate('/chat')} />}
  </main>
}

function SubmittedModal({ number, submittedAt, heirCount, documentCount, pill, onClose, onChat }: { number: string; submittedAt: string | null; heirCount: number; documentCount: number; pill: StatusPill; onClose: () => void; onChat: () => void }) {
  const primary = useRef<HTMLButtonElement>(null)
  useEffect(() => { primary.current?.focus() }, [])
  useEffect(() => { const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }; window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey) }, [onClose])
  return <div className="v17-done" role="presentation" onClick={onClose}>
    <section className="v17-done__dialog" role="dialog" aria-modal="true" aria-labelledby="c02-submitted-title" onClick={event => event.stopPropagation()}>
      <div className="v17-done__top"><span className="v17-done__ring" aria-hidden="true"><span><svg width="24" height="24" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" /></svg></span></span>
        <h2 id="c02-submitted-title">상속예금 사전접수가 등록되었습니다</h2></div>
      <dl className="v17-done__summary">
        <dt>업무 유형</dt><dd className="is-strong">상속예금 해지 및 지급 사전접수</dd>
        <dt>접수번호</dt><dd className="is-number">{number}</dd>
        <dt>접수 일시</dt><dd>{formatDateTime(submittedAt)}</dd>
        <dt>제출 내용</dt><dd>상속인 {heirCount}명 정보 · 업로드 서류 {documentCount}건</dd>
        <dt>서명·인증</dt><dd>요청서·개인정보 동의서 서명 완료 · 방문 상속인 서명은 영업점에서</dd>
        <dt>현재 상태</dt><dd><span className="v17-done__pill">{pill.label}</span></dd>
      </dl>
      <ol className="v17-done__steps">
        <li><span>1</span><span>AI가 제출 내용을 먼저 점검해요. 확인이 필요한 내용이 있으면 같은 접수 건에서 질문드려요.</span></li>
        <li><span>2</span><span>담당 직원이 원본과 내용을 검토해 결과를 확정해요.</span></li>
        <li><span>3</span><span>결과를 웹 알림으로 안내해요. 결과가 ‘진행 가능’이면 그때 방문할 영업점과 시간을 예약해요.</span></li>
      </ol>
      <p className="v17-done__note"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M12 11v5M12 8h.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>직원이 검토를 시작하기 전까지는 업무 요청 기록에서 접수를 취소할 수 있어요. 검토가 시작되면 취소할 수 없어요.</p>
      <div className="v17-done__actions"><button type="button" className="is-line" onClick={onChat}>채팅으로 돌아가기</button><button type="button" ref={primary} className="is-primary" onClick={onClose}>접수 상세 확인</button></div>
      <span className="v17-done__poc">PoC 모의 접수 · 실제 법정 신청 접수를 뜻하지 않아요</span>
    </section>
  </div>
}
