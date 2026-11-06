import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type C02CustomerCaseSnapshot, type Reservation } from '../api/taskApi'
import { caseNumber, casePill, formatDateTime, groupDocuments, heirCaption, lastActivityAt, timelineSteps, uploadProgress, visitConfirmation, visitLabel, type StatusPill } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'

const RESULT_COPY = {
  PROCEED: { title: '직원 검토 결과 · 진행 가능', body: '방문 전 서류 확인을 마쳤어요. 이제 방문할 영업점과 시간을 예약할 수 있어요.', tone: 'success' },
  REQUIREMENT_FAILED: { title: '직원 검토 결과 · 요건 미충족', body: '신청 요건을 충족하지 않아 이번 접수는 종료됐어요. 자세한 내용은 담당 직원 안내를 확인해 주세요.', tone: 'danger' },
  DOCUMENT_INSUFFICIENT: { title: '직원 검토 결과 · 서류 부족', body: '제출 서류로 확인이 어려워 이번 접수는 종료됐어요. 서류를 보완해 새로 신청할 수 있어요.', tone: 'danger' },
} as const
const RESERVATION_LABEL: Record<string, StatusPill> = {
  SELECTED: { label: '예약 · 선택됨', tone: 'info' }, PENDING_ASSIGNMENT: { label: '예약 · 담당자 배정 중', tone: 'info' },
  CONFIRMED: { label: '예약 확정', tone: 'success' }, NEEDS_MANUAL_ASSIGNMENT: { label: '예약 · 수동 배정 대기', tone: 'warn' },
}

const Pill = ({ pill }: { pill: StatusPill }) => <span className={`c02-pill c02-pill--${pill.tone}`}>{pill.label}</span>

/** Customer C-02 detail: intake/review status, staff result and the reservation entry point stay separate. */
export function C02CaseDetailPage() {
  const { caseId } = useParams(); const parsed = Number(caseId)
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [snapshot, setSnapshot] = useState<C02CustomerCaseSnapshot | null>(null)
  const [reservation, setReservation] = useState<Reservation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const valid = Number.isSafeInteger(parsed) && parsed > 0
  const load = useCallback(async () => {
    if (!taskBffEnabled || !valid) return
    try {
      const response = await c02Api.snapshot(parsed, `c02-detail-${Date.now()}`)
      setSnapshot(response.data); setError(null)
      if (response.data?.caseStatus === 'REVIEW_PASSED') {
        const reservations = await c02Api.reservations(`c02-detail-reservations-${Date.now()}`).catch(() => null)
        setReservation(reservations?.data?.find(item => item.caseId === parsed && item.status !== 'CANCELED') ?? null)
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

  return <main className="c02-detail">
    <nav className="c02-breadcrumb" aria-label="현재 위치"><Link to="/">홈</Link><span>›</span><Link to="/tasks/c02">상속예금 접수 내역</Link><span>›</span><b>상세</b></nav>
    {error && <p className="c02-alert" role="alert">{error}</p>}
    <section className="c02-detail__card c02-detail__head">
      <div className="c02-detail__titlebar"><div><span className="c02-tag">C · 직원 검토</span><h1>상속예금 해지 및 지급 사전접수</h1></div><div className="c02-pills">{pills.map(pill => <Pill key={pill.label} pill={pill} />)}</div></div>
      <dl className="c02-meta"><div><dt>접수번호</dt><dd className="c02-mono">{number}</dd></div><div><dt>접수 일시</dt><dd>{formatDateTime(snapshot.timeline?.submittedAt)}</dd></div><div><dt>업무 번호</dt><dd>#{snapshot.workItemId}</dd></div><div><dt>최근 변경</dt><dd>{formatDateTime(lastActivityAt(snapshot))}</dd></div></dl>
      {snapshot.caseStatus === 'DRAFT' && <div className="c02-detail__actions"><Link className="c02-primary c02-link-button" to={`/tasks/c02/${snapshot.workItemId}`}>접수 이어서 작성</Link></div>}
      {snapshot.caseStatus === 'SUBMITTED' && <div className="c02-detail__actions c02-cancel">
        {confirmCancel
          ? <><span>접수를 취소할까요? 취소한 접수는 되돌릴 수 없고, 다시 신청하면 새 접수로 진행돼요.</span><button type="button" className="c02-danger" disabled={busy} onClick={() => void cancelCase()}>취소하기</button><button type="button" className="c02-secondary" disabled={busy} onClick={() => setConfirmCancel(false)}>돌아가기</button></>
          : <><button type="button" className="c02-danger" onClick={() => setConfirmCancel(true)}>접수 취소</button><span>직원이 검토를 시작하기 전까지만 취소할 수 있어요.</span></>}
      </div>}
    </section>

    {decision && result && <section className={`c02-detail__card c02-result c02-result--${result.tone}`}>
      <header><span className="c02-result__icon" aria-hidden="true">{result.tone === 'success' ? '✓' : '!'}</span><div><h2>{result.title}</h2><p>{result.body}</p></div></header>
      <div className="c02-result__body">
        <strong>담당 직원 안내 (원문)</strong>
        <p className="c02-quote">{decision.customerMessage}</p>
        <small>담당 직원 확정 · {formatDateTime(snapshot.timeline?.resultNotifiedAt ?? snapshot.timeline?.decidedAt)} · 웹 알림 발송</small>
        {decision.outcome === 'PROCEED' && <>
          <strong>방문할 때 가져올 것</strong>
          <ul className="c02-bring">{groups.visitOnly.map(row => <li key={row.key}><span>{row.title}</span><b>방문 시 필수</b></li>)}<li><span>업로드한 서류의 원본 (3개월 이내 발급)</span><b>방문 시 필수</b></li>{heirs.filter(heir => heir.visitStatus === 'UNCONFIRMED_ABSENT').map(heir => <li key={heir.personRef} className="is-conditional"><span>{heir.name}님이 오지 못하는 경우: 신분증 사본, 인감증명서(본인발급용·3개월 이내), 인감 날인 위임장 — 또는 AX뱅크 앱 위임장 접수번호</span><b>불참 시</b></li>)}</ul>
          <p className="c02-notice">‘진행 가능’과 방문 예약은 예금 지급이 확정되었다는 뜻이 아니에요. 실제 지급 여부는 방문했을 때 영업점에서 원본을 확인한 뒤 정해져요.</p>
        </>}
        {decision.outcome !== 'PROCEED' && <p className="c02-notice">이 결과는 사전 검토 결과이며 법정 통지·최종 결정이 아니에요. 다시 신청하면 새 접수로 진행돼요.</p>}
      </div>
    </section>}

    <section className={`c02-detail__card c02-reservation${snapshot.caseStatus === 'REVIEW_PASSED' && !reservation ? ' is-open' : ''}`}>
      <div className="c02-reservation__title"><h2>방문 예약</h2><span className="c02-pill c02-pill--muted">{reservation ? (RESERVATION_LABEL[reservation.status]?.label ?? reservation.status) : snapshot.caseStatus === 'REVIEW_PASSED' ? '예약 전' : '결과 대기'}</span></div>
      {snapshot.caseStatus === 'REVIEW_PASSED'
        ? reservation
          ? <p>{formatDateTime(reservation.startsAt)} 방문으로 예약돼 있어요. {reservation.status === 'CONFIRMED' ? '담당 직원이 배정됐어요.' : '담당 직원 배정을 기다리고 있어요. 배정 전에는 예약 확정이 아니에요.'}</p>
          : <div className="c02-reservation__row"><p>아직 예약하지 않았어요. 가까운 영업점과 방문 시간을 골라 주세요. 예약하면 그 지점의 담당 직원이 배정돼요.</p><button type="button" className="c02-primary" onClick={() => navigate(`/tasks/c02/reservations?caseId=${snapshot.caseId}`)}>방문 영업점 예약</button></div>
        : <p>{decision ? '이 접수는 방문 예약 대상이 아니에요.' : '직원 검토 결과를 기다리고 있어요. 결과가 ‘진행 가능’이면 알림을 받은 뒤 방문할 영업점과 시간을 예약할 수 있어요. 지금은 예약 정보가 없어요.'}</p>}
    </section>

    <div className="c02-detail__grid">
      <section className="c02-detail__card"><h2>진행 타임라인</h2><p className="c02-subtitle">접수·검토와 방문 예약은 따로 표시돼요</p>
        <ol className="c02-timeline">{timelineSteps(snapshot, snapshot.timeline).map(step => <li key={step.label} className={`is-${step.state}`}><b>{step.label}</b><span>{step.at ? formatDateTime(step.at) : step.state === 'pending' ? '대기' : step.state === 'current' ? '진행 중' : '—'}{step.note ? ` · ${step.note}` : ''}</span></li>)}</ol>
      </section>
      <div className="c02-detail__stack">
        <section className="c02-detail__card"><h2>상속인별 방문 계획</h2>
          <div className="c02-table-wrap"><table className="c02-table"><thead><tr><th>상속인</th><th>입력한 방문 방식</th><th>확인 상태</th></tr></thead><tbody>{heirs.map(heir => <tr key={heir.personRef}><td>{heir.name} ({heirCaption(heir)})</td><td>{visitLabel(heir)}</td><td>{heir.visitStatus === 'UNCONFIRMED_ABSENT' ? <span className="c02-pill c02-pill--warn">{visitConfirmation(heir)}</span> : visitConfirmation(heir)}</td></tr>)}</tbody></table></div>
          {heirs.some(heir => heir.visitStatus === 'UNCONFIRMED_ABSENT') && <p className="c02-helper c02-helper--muted">방문 여부 확인 질문에 답이 없어, 준비서류를 안내하기 위해 <b>잠정 불참</b>으로 표시했어요. 확정된 불참이 아니며, 최종 확인은 직원이 해요.</p>}
        </section>
        <section className="c02-detail__card"><div className="c02-card__title"><h2>접수 진행 현황</h2><span>필수정보 <b>입력 완료</b> · 업로드 서류 <b>{rows.filter(row => row.uploaded).length}/{rows.length}</b>{progress.missingRequired > 0 ? ` · 필수 ${progress.missingRequired}건 미제출` : ''}</span></div>
          <div className="c02-table-wrap"><table className="c02-table"><thead><tr><th>서류 · 제출자</th><th>제출됨</th><th>시스템 확인</th><th>직원 확인</th></tr></thead><tbody>{rows.map(row => <tr key={row.key}><td>{row.title}</td><td className="c02-center">{row.uploaded ? <b className="c02-ok">✓</b> : '—'}</td><td className="c02-small">{row.uploaded?.status === 'SYSTEM_VALIDATED' ? '형식 확인' : '해당 없음 · 원문 확인'}</td><td className="c02-small">{row.uploaded?.status === 'HUMAN_VERIFIED' ? <b className="c02-ok">확인 완료</b> : decision ? '검토 결과에 반영' : '대기'}</td></tr>)}</tbody></table></div>
          <p className="c02-helper c02-helper--muted">일반 증명서는 내용을 자동으로 읽지 않아 시스템 확인 대상이 아니에요. 직원이 원문을 확인해요.</p>
        </section>
      </div>
    </div>

    {showModal && <SubmittedModal number={number} submittedAt={snapshot.timeline?.submittedAt ?? null} heirCount={heirs.length} documentCount={rows.filter(row => row.uploaded).length} pill={pills[0]}
      onClose={() => { params.delete('submitted'); setParams(params, { replace: true }) }} onChat={() => navigate('/chat')} />}
  </main>
}

function SubmittedModal({ number, submittedAt, heirCount, documentCount, pill, onClose, onChat }: { number: string; submittedAt: string | null; heirCount: number; documentCount: number; pill: StatusPill; onClose: () => void; onChat: () => void }) {
  const primary = useRef<HTMLButtonElement>(null)
  useEffect(() => { primary.current?.focus() }, [])
  useEffect(() => { const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }; window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey) }, [onClose])
  return <div className="c02-modal" role="presentation" onClick={onClose}>
    <div className="c02-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="c02-submitted-title" onClick={event => event.stopPropagation()}>
      <span className="c02-modal__check" aria-hidden="true">✓</span>
      <h2 id="c02-submitted-title">상속예금 사전접수가 등록되었습니다</h2>
      <dl className="c02-modal__summary">
        <div><dt>업무 유형</dt><dd><b>상속예금 해지 및 지급 사전접수</b></dd></div>
        <div><dt>접수번호</dt><dd className="c02-mono c02-accent">{number}</dd></div>
        <div><dt>접수 일시</dt><dd>{formatDateTime(submittedAt)}</dd></div>
        <div><dt>제출 내용</dt><dd>상속인 {heirCount}명 정보 · 업로드 서류 {documentCount}건</dd></div>
        <div><dt>서명·인증</dt><dd>요청서·개인정보 동의서 서명 완료 · 방문 상속인 서명은 영업점에서</dd></div>
        <div><dt>현재 상태</dt><dd><Pill pill={pill} /></dd></div>
      </dl>
      <ol className="c02-modal__steps">
        <li>AI가 제출 내용을 먼저 점검해요. 확인이 필요한 내용이 있으면 같은 접수 건에서 질문드려요.</li>
        <li>담당 직원이 원본과 내용을 검토해 결과를 확정해요.</li>
        <li>결과를 웹 알림으로 안내해요. 결과가 ‘진행 가능’이면 그때 방문할 영업점과 시간을 예약해요.</li>
      </ol>
      <p className="c02-notice">직원이 검토를 시작하기 전까지는 접수를 취소할 수 있어요. 검토가 시작되면 취소할 수 없어요.</p>
      <div className="c02-modal__actions"><button type="button" className="c02-secondary" onClick={onChat}>채팅으로 돌아가기</button><button type="button" ref={primary} className="c02-primary" onClick={onClose}>접수 상세 확인</button></div>
    </div>
  </div>
}
