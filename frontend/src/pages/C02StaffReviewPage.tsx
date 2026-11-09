import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02Document, type C02DocumentReviewHistory, type C02DocumentReviewOverview, type C02DocumentReviewRow, type C02DocumentSlot, type C02ReviewTimelineEntry, type C02HeirView, type C02OcrSnapshot, type C02ReviewItem, type C02StaffCase } from '../api/taskApi'
import { useAuth } from '../auth/AuthContext'
import { formatDateTime } from '../features/c02/c02CaseView'
import { toUserMessageOr } from '../api/errorMessages'
import { c02DocumentLabel } from '../ui/customerLabels'
import { hasCurrentReview } from '../features/c02/c02CaseView'
import { AiTag, C02AiDraftCard, C02AiReviewPanel, type AiCandidate, type AiStep } from '../features/c02/C02AiReview'
import { C02OcrResults } from '../features/c02/C02OcrResults'
import { isOcrItem, ocrStatusLabel } from '../features/c02/c02OcrView'
import { STAFF_INPUT_MARK, agentItemView, customerNoticeDraft, decisionGuide, noticeDocumentLines, runLabel, staffSummaryLines, statusLabel } from '../features/c02/c02StaffText'
import { PolicyNoticePanel } from '../features/staff/PolicyNoticePanel'
import { C02StaffCaseQa } from '../features/c02/C02CaseQa'
import '../features/c02/staff-review.css'
import '../features/c02/staff-v17.css'

type Outcome = 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'
export type View = 'detail' | 'review' | 'final'
type Bucket = 'ok' | 'conflict' | 'customer' | 'staff' | 'fail'

const requestId = (action: string, caseId: number) => `c02-staff-${action}-${caseId}-${crypto.randomUUID()}`
const POWER_OF_ATTORNEY = 'ABSENT_HEIR_POWER_OF_ATTORNEY'
const TIMELINE_LABEL: Record<string, string> = {
  C02_CLARIFICATION_REQUESTED: '고객 추가 확인 요청', C02_CLARIFICATION_ANSWERED: '고객 답변', C02_CLARIFICATION_EXPIRED: '답변 기한 지남', C02_STAFF_POLICY_QUESTION: '업무기준 질문',
  C02_REVIEW_STARTED: '직원 검토 시작', DOCUMENT_STAFF_REVIEWED: '서류 직원 확인', C02_STAFF_DECIDED: '검토 결과 등록', C02_CANCELED_BY_CUSTOMER: '고객 접수 취소', C02_DEMO_SEEDED: '시연 데이터 생성',
  'AGENT_RUN:STAFF_PRECHECK': 'AI 사전점검', 'AGENT_RUN:C02_OCR_PRECHECK': '위임장 OCR 대조', 'AGENT_RUN:STAFF_BRIEF': 'AI 검토 의견',
}
const TASK_NAME = '상속예금 해지 및 지급 사전접수'

const OUTCOMES: { value: Outcome; label: string; desc: string; tone: string }[] = [
  { value: 'PROCEED', label: '진행 가능', desc: '방문 예약 안내가 고객에게 열려요', tone: 'ok' },
  { value: 'REQUIREMENT_FAILED', label: '요건 미충족', desc: '신청 요건 불충족 · 접수 종료', tone: 'danger' },
  { value: 'DOCUMENT_INSUFFICIENT', label: '서류 부족', desc: '서류로 확인 불가 · 접수 종료 · 재신청은 새 Case', tone: 'caution' },
]
const BUCKETS: { key: Bucket; label: string }[] = [
  { key: 'ok', label: '정상 확인' }, { key: 'conflict', label: '불일치 후보' }, { key: 'customer', label: '고객 추가 확인' },
  { key: 'staff', label: '직원 확인 필요' }, { key: 'fail', label: '분석 불가' },
]

/** The five result-report groups of spec 09-3, from the stored result codes only. */
function bucketOf(item: C02ReviewItem): Bucket {
  const result = item.result ?? ''
  if (['AUTO_MATCHED', 'TYPE_MATCHED', 'PASS', 'OK'].includes(result)) return 'ok'
  if (['MISMATCH', 'CONFLICT_CANDIDATE'].includes(result)) return 'conflict'
  if (['WAITING_CUSTOMER', 'CLARIFICATION_REQUESTED'].includes(result)) return 'customer'
  if (['FAILED', 'NOT_EXTRACTED', 'TYPE_UNVERIFIABLE', 'TYPE_MISMATCH', 'OCR_FAILED'].includes(result)) return 'fail'
  return 'staff'
}

function itemView(item: C02ReviewItem) {
  if (isOcrItem(item)) return { title: '위임장 자동 대조', body: item.message ?? '자동 대조 결과예요.', tag: '규칙', by: 'Spring 대조' }
  const view = agentItemView(item)
  return { title: view.title, body: view.body, tag: item.source === 'AGENT_SEMANTIC' ? 'AI' : '규칙', by: item.source === 'AGENT_SEMANTIC' ? 'Agent' : view.source }
}

const heirLabel = (heir: C02HeirView) => `${heir.name} (${heir.relationship}${heir.applicant ? ' · 신청인' : ''})`
const visitInput = (heir: C02HeirView) => heir.visitStatus === 'ATTENDING' ? '방문' : heir.visitStatus === 'UNCONFIRMED_ABSENT' ? '방문 여부 미응답' : heir.delegationMethod === 'DOCUMENT' ? '불참 · 서류 위임' : heir.delegationMethod === 'RECEIPT' ? '불참 · 앱 위임장' : '불참'

function latestBySlot(documents: C02Document[]) {
  const latest = new Map<string, C02Document>()
  for (const file of documents) {
    const current = latest.get(file.documentSlotKey)
    if (!current || file.version > current.version) latest.set(file.documentSlotKey, file)
  }
  return latest
}
const slotKeyOf = (slot: C02DocumentSlot) => `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`
const sizeLabel = (bytes: number) => bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(bytes / 1024))}KB`

/** Staff actions are possible only behind a staff-session BFF; Agent output remains reference material. */
export function C02StaffReviewPage({ view = 'detail' }: { view?: View }) {
  const { caseId } = useParams()
  const parsedId = Number(caseId)
  const navigate = useNavigate()
  const { user } = useAuth()
  const [search] = useSearchParams()
  const selectedFile = search.get('file') ? Number(search.get('file')) : null
  const [reviews, setReviews] = useState<C02DocumentReviewOverview | null>(null)
  const [history, setHistory] = useState<C02DocumentReviewHistory[]>([])
  const [reason, setReason] = useState('')
  const [exceptionReason, setExceptionReason] = useState('')
  const [timeline, setTimeline] = useState<C02ReviewTimelineEntry[]>([])
  const [openQuestions, setOpenQuestions] = useState(0)
  const [saving, setSaving] = useState(false)
  const [reviewMessage, setReviewMessage] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const [data, setData] = useState<C02StaffCase | null>(null)
  const [ocr, setOcr] = useState<C02OcrSnapshot | null>(null)
  const [outcome, setOutcome] = useState<Outcome | ''>('')
  const [message, setMessage] = useState('')
  const filledNotice = useRef('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [bucket, setBucket] = useState<Bucket | null>(null)
  const [preview, setPreview] = useState<{ id: number; url: string; mime: string } | null>(null)
  const [bring, setBring] = useState<Record<string, boolean>>({})
  const [confirming, setConfirming] = useState(false)

  const load = async () => {
    if (!Number.isSafeInteger(parsedId) || parsedId <= 0) { setError('Case ID가 올바르지 않습니다.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.staffCase(parsedId, requestId('case', parsedId))
      const caseData = response.data ?? null
      setData(caseData)
      if (caseData) {
        try { const ocrResponse = await c02Api.staffOcr(parsedId, caseData.caseView.revision, requestId('ocr', parsedId)); setOcr(ocrResponse.data ?? null) }
        catch { setOcr(null) }
      } else setOcr(null)
      await loadReviews()
    } catch (cause) {
      setError(toUserMessageOr(cause, '직원 Case를 불러오지 못했습니다.'))
    } finally { setBusy(false) }
  }

  // STAFF-06: the saved state, not the screen, decides what counts as checked.
  const loadReviews = async () => {
    try {
      const [overview, events] = await Promise.all([
        c02Api.documentReviews(parsedId, requestId('reviews', parsedId)),
        c02Api.documentReviewHistory(parsedId, requestId('review-history', parsedId)),
      ])
      setReviews(overview.data ?? null); setHistory(events.data ?? [])
    } catch { setReviews(null); setHistory([]) }
    try { setTimeline((await c02Api.reviewTimeline(parsedId, requestId('timeline', parsedId))).data ?? []) } catch { setTimeline([]) }
    try { setOpenQuestions(((await c02Api.staffClarifications(parsedId, requestId('questions', parsedId))).data ?? []).filter(row => row.status === 'OPEN').length) } catch { setOpenQuestions(0) }
  }

  const saveReview = async (row: C02DocumentReviewRow, result: 'CONFIRMED' | 'SUPPLEMENT_REQUIRED') => {
    if (row.documentFileId === null || row.fileVersion === null) return
    const note = reason.trim()
    if (result === 'SUPPLEMENT_REQUIRED' && !note) { setReviewMessage({ tone: 'error', text: '보완이 필요한 이유를 적어 주세요. 결과 등록 때 고객 안내에 쓸 수 있어요.' }); return }
    setSaving(true); setReviewMessage(null)
    try {
      await c02Api.reviewDocument(parsedId, row.documentFileId, { fileVersion: row.fileVersion, result, reason: note || null, expectedReviewId: row.review?.eventId ?? null, requestId: requestId('doc-review', parsedId) })
      setReason('')
      await loadReviews()
      setReviewMessage({ tone: 'ok', text: result === 'CONFIRMED' ? '확인 완료로 저장했어요.' : '보완 필요로 저장했어요.' })
    } catch (cause) {
      setReviewMessage({ tone: 'error', text: toUserMessageOr(cause, '서류 확인 결과를 저장하지 못했어요. 다시 불러온 뒤 시도해 주세요.') })
      await loadReviews()
    } finally { setSaving(false) }
  }

  // Open the Case as soon as the staff member arrives from the queue.
  useEffect(() => { if (taskBffEnabled) void load() }, [parsedId]) // eslint-disable-line react-hooks/exhaustive-deps

  // The window opens synchronously so the popup blocker allows it; the authenticated file is loaded afterwards.
  const openOriginal = async (file: C02Document) => {
    const target = window.open('', '_blank')
    try {
      const blob = await c02Api.staffDocument(parsedId, file.documentFileId)
      const url = URL.createObjectURL(blob)
      if (target) target.location.href = url; else window.location.href = url
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (cause) { target?.close(); setError(toUserMessageOr(cause, '원본 파일을 열지 못했습니다.')) }
  }

  // The review view shows the selected original inline; the same authenticated download is used.
  useEffect(() => {
    if (view !== 'review' || selectedFile === null || !data) return
    const file = data.documents.find(item => item.documentFileId === selectedFile)
    if (!file) return
    let active = true
    let url: string | null = null
    c02Api.staffDocument(parsedId, file.documentFileId)
      .then(blob => { if (!active) return; url = URL.createObjectURL(blob); setPreview({ id: file.documentFileId, url, mime: blob.type || file.mimeType }) })
      .catch(() => { if (active) setPreview(null) })
    return () => { active = false; if (url) URL.revokeObjectURL(url) }
  }, [view, selectedFile, data, parsedId])

  const startReview = async () => {
    const current = data?.caseView
    if (!current) return
    setBusy(true); setError(null); setNotice(null)
    try {
      await c02Api.startReview(parsedId, { revision: current.revision, requestId: requestId('start', parsedId) })
      setNotice('검토를 시작했어요. 이제 고객은 접수를 취소할 수 없어요.')
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '검토를 시작하지 못했습니다.'))
    } finally { setBusy(false) }
  }

  const precheck = async () => {
    const current = data?.caseView
    if (!current || !Number.isSafeInteger(parsedId)) { setError('최신 Case를 먼저 조회해 주세요.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      await c02Api.staffPrecheck(parsedId, { revision: current.revision, requestId: requestId('precheck', parsedId) })
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '직원 Agent 사전점검을 실행하지 못했습니다.'))
    } finally { setBusy(false) }
  }

  const decide = async () => {
    const current = data?.caseView
    const review = data?.precheck
    setConfirming(false)
    if (!outcome) { setError('검토 결과를 선택해 주세요.'); return }
    if (message.includes(STAFF_INPUT_MARK)) { setError('안내 문구의 [직원 입력] 부분을 채운 뒤 등록해 주세요.'); return }
    if (!current || !review || review.revision !== current.revision || !Number.isSafeInteger(parsedId)) {
      setError('현재 Case revision의 사전점검 결과를 먼저 확인해 주세요.')
      return
    }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.staffDecision(parsedId, { revision: current.revision, requestId: requestId('decision', parsedId), outcome, customerMessage: message, exceptionReason: openChecks > 0 && outcome === 'PROCEED' ? exceptionReason.trim() || null : null })
      const notification = response.data?.notification
      setNotice(notification ? '검토 결과를 등록하고 고객에게 웹 알림을 보냈어요.' : '검토 결과를 등록했어요.')
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '최종결정을 저장할 수 없습니다.'))
    } finally { setBusy(false) }
  }

  /** A manually written message is replaced only after staff agree; regenerating never touches it. */
  const applyDraft = (draft: string) => {
    if (message.trim() && message !== draft && !window.confirm('작성 중인 고객 안내를 초안으로 바꿀까요?')) return
    setMessage(draft)
  }

  const noticeLines = noticeDocumentLines(reviews?.documents ?? [])

  /** Picking a result fills the notice, unless staff already wrote their own. */
  const chooseOutcome = (value: Outcome) => {
    setOutcome(value)
    if (message.trim() && message !== filledNotice.current) return
    const draft = customerNoticeDraft(value, noticeLines) ?? ''
    filledNotice.current = draft
    setMessage(draft)
  }

  const current = data?.caseView
  const currentReview = hasCurrentReview(data?.precheck, current?.revision)
  const submitted = current?.caseStatus === 'SUBMITTED'
  const inReview = current?.caseStatus === 'IN_REVIEW'
  const canPrecheck = Boolean(taskBffEnabled && current && submitted && !data?.finalDecision && !currentReview && !busy)
  // 검토 시작 locks out customer cancellation; the result can only be registered after that (spec 3-2).
  const canStart = Boolean(taskBffEnabled && current && submitted && currentReview && !data?.finalDecision && !busy)
  const canDecide = Boolean(taskBffEnabled && current && inReview && currentReview && !data?.finalDecision && !busy && outcome)

  const derived = useMemo(() => {
    if (!data) return null
    const items = data.precheck?.reviewItems ?? []
    const heirs = data.intake?.heirs ?? []
    const byRef = new Map(heirs.map(heir => [heir.personRef, heir]))
    const latest = latestBySlot(data.documents)
    const submittedFiles = data.requiredDocuments
      .map(slot => ({ slot, file: latest.get(slotKeyOf(slot)) ?? null }))
      .filter((row): row is { slot: C02DocumentSlot; file: C02Document } => row.file !== null)
    const counts = Object.fromEntries(BUCKETS.map(entry => [entry.key, items.filter(item => bucketOf(item) === entry.key).length])) as Record<Bucket, number>
    const ocrItems = items.filter(isOcrItem)
    const candidates: AiCandidate[] = ocrItems.filter(item => item.result === 'MISMATCH')
      .map(item => ({ status: '확인 못 함', tone: 'danger', title: '위임장 자동 대조', body: item.message ?? '신청정보와 위임장 값이 달라요.' }))
    const applicant = heirs.find(heir => heir.applicant) ?? null
    return {
      items, heirs, byRef, submittedFiles, counts, candidates, applicant,
      tally: { ok: ocrItems.filter(item => item.result === 'AUTO_MATCHED').length, un: candidates.length, need: counts.staff },
      summary: staffSummaryLines(data),
      visitOnly: data.requiredDocuments.filter(slot => slot.requirement === 'VISIT_ONLY'),
    }
  }, [data])

  const caseNo = current ? `Case #${current.caseId}` : ''
  const customer = derived?.applicant?.name ?? '신청인 정보 없음'
  const outcomeLabel = OUTCOMES.find(entry => entry.value === outcome)?.label ?? null
  const reviewOf = (fileId: number) => reviews?.documents.find(row => row.documentFileId === fileId)?.review ?? null
  const reviewRowOf = (fileId: number) => reviews?.documents.find(row => row.documentFileId === fileId) ?? null
  const judgedOk = reviews?.summary.confirmed ?? 0
  const judgedSup = reviews?.summary.supplement ?? 0
  const remaining = reviews?.summary.unreviewed ?? (derived?.submittedFiles.length ?? 0)
  const openChecks = (reviews?.summary.supplement ?? 0) + (reviews?.summary.unreviewed ?? 0)
  const needsException = outcome === 'PROCEED' && openChecks > 0
  const isReviewer = Boolean(reviews?.reviewerId != null && user?.userId === reviews?.reviewerId)
  const canJudge = Boolean(reviews?.editable && isReviewer && !saving)
  const judgeBlocked = !reviews ? '서류 확인 현황을 불러오지 못했어요.' : reviews.caseStatus === 'SUBMITTED' ? 'Case 상세에서 [검토 시작]을 누른 뒤 서류를 확인할 수 있어요.'
    : !reviews.editable ? '결과가 등록된 Case라 서류 확인을 바꿀 수 없어요.' : !isReviewer ? `이 Case는 ${reviews.reviewerName ?? '다른 직원'}님이 검토 중이라 확인 결과를 저장할 수 없어요.` : null
  const base = `/staff/c02/${parsedId}`
  const openReview = (fileId?: number) => { const id = fileId ?? derived?.submittedFiles[0]?.file.documentFileId; navigate(`${base}/documents${id ? `?file=${id}` : ''}`); window.scrollTo(0, 0) }
  const pickFile = (fileId: number) => { setReason(''); setReviewMessage(null); navigate(`${base}/documents?file=${fileId}`, { replace: true }) }
  const openFinal = () => { navigate(`${base}/result`); window.scrollTo(0, 0) }
  const openDetail = () => { navigate(base); window.scrollTo(0, 0) }

  const banners = <>
    {!taskBffEnabled && <p className="v17-alert" role="status">직원 로그인 BFF 연결이 비활성화되어 있습니다. 화면은 내부 gateway token을 사용하지 않습니다.</p>}
    {error && <p className="v17-alert" role="alert">{error}</p>}
    {notice && <p className="v17-notice" role="status">{notice} <Link to="/staff/queue">업무함으로 돌아가기</Link></p>}
  </>

  if (!data || !current || !derived) return <main className="v17-sd">
    <Link className="v17-back" to="/staff/queue"><Chevron />사전검토 업무함</Link>
    {banners}
    {!error && <section className="v17-card"><h2 className="v17-h2">검토할 Case를 불러오고 있어요</h2><p className="v17-muted">Case ID와 직원 권한을 확인한 뒤 현재 revision, 문서와 사전점검 결과를 표시합니다.</p></section>}
  </main>

  const steps: AiStep[] = [
    { who: '규칙', text: `서류 ${derived.submittedFiles.length}건 수신 · 필요서류 슬롯·형식 확인`, state: '완료', tone: 'done' },
    { who: 'AI', text: `사전점검 r${data.precheck?.revision ?? current.revision} · ${runLabel(data.precheck?.agentRunStatus)}`, state: currentReview ? '완료' : '대기', tone: currentReview ? 'done' : 'wait' },
    { who: '직원', text: '원본 확인 · 결과 판단 · 결과 등록', state: data.finalDecision ? '완료' : inReview ? '진행 중' : '대기', tone: data.finalDecision ? 'done' : inReview ? 'doing' : 'wait' },
  ]

  if (view === 'review') {
    const rows = derived.submittedFiles
    const selected = rows.find(row => row.file.documentFileId === selectedFile) ?? rows[0] ?? null
    const isPoa = selected?.slot.documentTypeCode === POWER_OF_ATTORNEY
    const owner = selected?.slot.personRef ? derived.byRef.get(selected.slot.personRef)?.name ?? '상속인' : '공통'
    const currentRow = selected ? reviewRowOf(selected.file.documentFileId) : null
    const judgement = currentRow?.review?.result
    const fileHistory = selected ? history.filter(entry => entry.documentTypeCode === selected.slot.documentTypeCode && (entry.personName ?? null) === (selected.slot.personRef ? derived.byRef.get(selected.slot.personRef)?.name ?? null : null)) : []
    const relatedItems = selected ? derived.items.filter(item => item.documentFileId === selected.file.documentFileId && !isOcrItem(item)) : []
    return <div className="v17-sr">
      <div className="v17-sr__bar">
        <button type="button" className="v17-back" onClick={openDetail}><Chevron />Case 상세</button>
        <span className="v17-sr__sep" aria-hidden="true" />
        <h1>제출서류 확인</h1>
        <span className="v17-sr__meta"><span className="v17-mono">{caseNo}</span> · {customer} · C-02 {TASK_NAME} · {statusLabel(current.caseStatus)}</span>
      </div>
      {banners}
      <div className="v17-sr__cols">
        <nav className="v17-sr__list" aria-label="제출 서류">
          <span className="v17-sr__count">제출 서류 {rows.length}건 · 직원 확인 {judgedOk + judgedSup}/{rows.length}</span>
          {rows.length === 0 && <p className="v17-muted">제출된 서류가 없어요.</p>}
          {rows.map(row => { const mark = reviewOf(row.file.documentFileId)?.result; const on = row.file.documentFileId === selected?.file.documentFileId; return <button type="button" key={row.file.documentFileId} className={on ? 'is-on' : undefined} aria-pressed={on} onClick={() => pickFile(row.file.documentFileId)}>
            <strong>{c02DocumentLabel(row.slot.documentTypeCode)}{row.slot.personRef ? ` · ${derived.byRef.get(row.slot.personRef)?.name ?? '상속인'}` : ''}</strong>
            <span><span className={`v17-chip ${row.slot.documentTypeCode === POWER_OF_ATTORNEY ? 'v17-chip--info' : 'v17-chip--muted'}`}>{row.slot.documentTypeCode === POWER_OF_ATTORNEY ? 'OCR 대상' : 'OCR 미적용'}</span><span className={`v17-chip ${mark === 'CONFIRMED' ? 'v17-chip--ok' : mark === 'SUPPLEMENT_REQUIRED' ? 'v17-chip--caution' : 'v17-chip--muted'}`}>{mark === 'CONFIRMED' ? '확인 완료' : mark === 'SUPPLEMENT_REQUIRED' ? '보완 필요' : '미확인'}</span></span>
          </button> })}
        </nav>
        <section className="v17-sr__viewer" aria-label="원본 보기">
          <div className="v17-sr__viewer-head"><strong>{selected?.file.fileName ?? '선택한 서류 없음'}</strong>{selected && <><span className="v17-muted-s">v{selected.file.version} · {sizeLabel(selected.file.sizeBytes)}</span><button type="button" onClick={() => void openOriginal(selected.file)}>새 창으로 열기</button></>}</div>
          <div className="v17-sr__page">
            {!selected ? <p className="v17-muted">왼쪽에서 서류를 골라 주세요.</p>
              : preview && preview.id === selected.file.documentFileId
                ? preview.mime.startsWith('image/') ? <img src={preview.url} alt={`${c02DocumentLabel(selected.slot.documentTypeCode)} 원본`} /> : <iframe src={preview.url} title={`${c02DocumentLabel(selected.slot.documentTypeCode)} 원본`} />
                : <p className="v17-muted">원본을 불러오고 있어요.</p>}
          </div>
        </section>
        <aside className="v17-sr__side">
          {selected ? <>
            <div className="v17-sr__title"><span>확인 중인 서류</span><h2>{c02DocumentLabel(selected.slot.documentTypeCode)}</h2><span>{owner} · {selected.file.mimeType.split('/')[1]?.toUpperCase()} · {sizeLabel(selected.file.sizeBytes)} · 파일 v{selected.file.version} · 접수 r{selected.file.caseRevision}</span></div>
            {isPoa ? <section className="v17-sr__ai">
              <div className="v17-sr__ai-head"><AiTag /><h3>AI 검토 보조</h3></div>
              <div className="v17-sr__ai-body"><span className="v17-muted-s">공식 양식이라 OCR로 읽은 값을 신청정보와 대조했어요. 서명·인감과 실제 위임 의사는 원본으로 확인해 주세요.</span>
                <C02OcrResults ocr={ocr} intake={data.intake} items={data.precheck?.reviewItems ?? []} /></div>
            </section> : <div className="v17-sr__plain"><span className="v17-chip v17-chip--outline">OCR 미적용 · 직원 원문 확인</span><span>일반 증명서라 본문을 자동 추출하지 않았어요. 시스템은 고객이 지정한 서류 종류·파일 형식·용량만 확인했어요. {ocrStatusLabel(selected.file, ocr)}</span></div>}
            {relatedItems.length > 0 && <ul className="v17-sr__items">{relatedItems.map((item, index) => { const v = itemView(item); return <li key={index}><strong>{v.title}</strong><span>{v.body}</span></li> })}</ul>}
            <fieldset className="v17-sr__judge" disabled={!canJudge}><legend>직원 판단 <span className="v17-tag v17-tag--staff">직원</span></legend>
              {currentRow?.review && <p className={`v17-sr__saved is-${currentRow.review.result === 'CONFIRMED' ? 'ok' : 'sup'}`}><strong>{currentRow.review.result === 'CONFIRMED' ? '확인 완료' : '보완 필요'}</strong> · {currentRow.review.staffName ?? '직원'} · {formatDateTime(currentRow.review.reviewedAt)}{currentRow.review.reason ? <><br />사유: {currentRow.review.reason}</> : null}</p>}
              <div><button type="button" className={judgement === 'CONFIRMED' ? 'is-ok' : undefined} aria-pressed={judgement === 'CONFIRMED'} onClick={() => currentRow && void saveReview(currentRow, 'CONFIRMED')}>확인 완료</button>
                <button type="button" className={judgement === 'SUPPLEMENT_REQUIRED' ? 'is-sup' : undefined} aria-pressed={judgement === 'SUPPLEMENT_REQUIRED'} onClick={() => currentRow && void saveReview(currentRow, 'SUPPLEMENT_REQUIRED')}>보완 필요</button></div>
              <label className="v17-sr__reason" htmlFor="v17-sr-reason">보완 필요 사유 <span>(보완 필요일 때 필수 · 255자)</span></label>
              <textarea id="v17-sr-reason" maxLength={255} value={reason} onChange={event => setReason(event.target.value)} placeholder="예: 인감증명서 발급일이 3개월을 넘었어요" />
              {reviewMessage && <p className={`v17-sr__msg is-${reviewMessage.tone}`} role={reviewMessage.tone === 'error' ? 'alert' : 'status'}>{reviewMessage.text}</p>}
              {judgeBlocked && <p className="v17-sr__msg is-info">{judgeBlocked}</p>}
              <span>서류별 판단은 고객에게 바로 알리지 않아요. 결과 등록 때 직원 의견으로 안내돼요. 확인 완료는 상태 HUMAN_VERIFIED, 보완 필요는 NEEDS_SUPPLEMENT로 저장되고 이력이 남아요.</span>
            </fieldset>
            {fileHistory.length > 0 && <div className="v17-sr__history"><strong>이 서류의 검토 이력</strong><ol>{fileHistory.map(entry => <li key={entry.eventId}><span>{formatDateTime(entry.reviewedAt)}</span><span>{entry.result === 'CONFIRMED' ? '확인 완료' : '보완 필요'} · {entry.staffName ?? '직원'} · 파일 v{entry.fileVersion}{entry.currentFile ? '' : ' (이전 파일)'}{entry.reason ? ` · ${entry.reason}` : ''}</span></li>)}</ol></div>}
          </> : <p className="v17-muted">확인할 서류가 없어요.</p>}
          <div className="v17-sr__grow" />
          <div className="v17-sr__foot">
            <span className={remaining > 0 ? 'is-warn' : 'is-done'}>{remaining > 0 ? `확인하지 않은 서류 ${remaining}건이 남았어요.` : '모든 서류를 확인했어요.'}</span>
            <div><button type="button" className="v17-btn v17-btn--ghost" onClick={openDetail}>Case 상세로</button>
              <button type="button" className="v17-btn v17-btn--primary" disabled={remaining > 0 || !reviews?.editable} onClick={openFinal}>확인 마치고 결과 등록</button></div>
          </div>
        </aside>
      </div>
    </div>
  }

  if (view === 'final') {
    const decided = data.finalDecision
    const outcomeEntry = OUTCOMES.find(entry => entry.value === (decided?.outcome ?? outcome)) ?? null
    return <main className="v17-sf">
      <div className="v17-sf__head">
        <button type="button" className="v17-back" onClick={openDetail}><Chevron />Case 상세</button>
        <h1>검토 결과 등록</h1>
        <span className="v17-sub"><span className="v17-mono">{caseNo}</span> · {TASK_NAME} · {customer} · {statusLabel(current.caseStatus)}</span>
      </div>
      {banners}
      {decided ? <section className="v17-sf__done">
        <span className="v17-sf__done-icon" aria-hidden="true"><svg width="28" height="28" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" /></svg></span>
        <strong>검토 결과를 등록했어요 · {outcomeEntry?.label ?? statusLabel(decided.outcome)}</strong>
        <span>{decisionGuide(data, outcome)} 고객에게 보낸 안내: {decided.customerMessage || '(안내 문구 없음)'}</span>
        <div><Link className="v17-btn v17-btn--primary" to="/staff/queue">사전검토 업무함으로</Link><button type="button" className="v17-btn v17-btn--line" onClick={openDetail}>Case 상세 보기</button></div>
      </section> : <div className="v17-sf__cols">
        <div className="v17-sf__main">
          <section className="v17-card v17-sf__sum">
            <h2>서류 확인 요약 <span className="v17-tag v17-tag--staff">직원</span></h2>
            <div><span>제출 {derived.submittedFiles.length}건</span><span className="is-ok">확인 완료 {judgedOk}건</span><span className="is-muted">보완 필요 {judgedSup}건</span><span className="is-warn">직원 확인 항목 {derived.summary.length}건</span></div>
            <span className="v17-muted-s">직원이 저장한 서류 확인 결과예요{reviews?.summary.lastReviewedAt ? ` · 마지막 확인 ${formatDateTime(reviews.summary.lastReviewedAt)}` : ''}. 미확인 {remaining}건. 검토 결과를 고르면 미제출·보완 서류와 사유가 고객 안내 문구에 채워져요.</span>
            {reviews && reviews.documents.some(row => row.review?.result === 'SUPPLEMENT_REQUIRED') && <ul className="v17-sf__sup">{reviews.documents.filter(row => row.review?.result === 'SUPPLEMENT_REQUIRED').map(row => <li key={row.requiredDocumentId}><strong>{c02DocumentLabel(row.documentTypeCode)}{row.personName ? ` · ${row.personName}` : ''}</strong> {row.review?.reason}</li>)}</ul>}
          </section>
          <C02AiDraftCard caseId={current.caseId} revision={current.revision} canGenerate={Boolean(submitted || inReview)} outcomeLabel={outcomeLabel} composedDraft={customerNoticeDraft(outcome, noticeLines)} onApply={applyDraft} />
          <section className="v17-card v17-sf__result">
            <h2>검토 결과</h2>
            <div className="v17-sf__options">{OUTCOMES.map(entry => <button type="button" key={entry.value} className={`is-${entry.tone}${outcome === entry.value ? ' is-on' : ''}`} aria-pressed={outcome === entry.value} onClick={() => chooseOutcome(entry.value)}><strong>{entry.label}</strong><span>{entry.desc}</span></button>)}</div>
            {outcome === 'PROCEED' && <div className="v17-sf__bring"><strong>방문 준비물 확정 (고객 예약 상세에 연결)</strong>
              {[...derived.visitOnly.map(slot => `${slot.personRef ? `${derived.byRef.get(slot.personRef)?.name ?? '상속인'} ` : ''}${c02DocumentLabel(slot.documentTypeCode)}`), '업로드한 서류의 원본 (사본 가능 서류 제외)'].map(item => <label key={item}><input type="checkbox" checked={bring[item] ?? true} onChange={event => setBring(map => ({ ...map, [item]: event.target.checked }))} /><span>{item}</span></label>)}
              <span>방문 준비물은 접수 규칙(Spring)이 정해요. 이 체크는 확인용이며 저장되지 않아요. 방문 예약은 예금 지급 확정이 아니라는 문구가 자동으로 붙어요.</span>
            </div>}
            <div className="v17-sf__opinion"><label htmlFor="v17-sf-message">고객에게 보낼 직원 의견 <span className="v17-tag v17-tag--staff">직원</span><span className="v17-muted-s">확정한 문구만 그대로 발송돼요</span></label>
              <textarea id="v17-sf-message" value={message} onChange={event => setMessage(event.target.value)} placeholder="결과 사유와 다음 안내를 적어 주세요" />
              <span className="v17-muted-s">{decisionGuide(data, outcome)}</span>
            </div>
          </section>
        </div>
        <aside className="v17-sf__side">
          <section className="v17-card v17-sf__preview"><h2>고객 안내 미리보기</h2>
            <div><span>웹 알림</span><strong>상속예금 사전검토 결과: {outcomeLabel ?? '결과를 선택해 주세요'}</strong><span>{caseNo} · 등록 즉시 발송</span></div>
            <div className="is-body"><span>안내 문구</span><span className="is-pre">{message.trim() || '(직원 의견을 입력하면 여기에 그대로 표시돼요)'}</span>{outcome === 'PROCEED' && <span className="is-link">방문 영업점 예약 안내 · 직원이 확인한 방문 준비물 포함</span>}<span className="is-fine">본 안내는 사전 검토 결과이며 법정 통지·최종 결정이 아니에요.</span></div>
          </section>
          <div className={`v17-sf__warn${outcome === 'PROCEED' ? ' is-info' : ''}`}>{!inReview ? '검토를 시작해야 결과를 등록할 수 있어요. Case 상세에서 [검토 시작]을 눌러 주세요.' : outcome === 'PROCEED' ? '‘진행 가능’을 등록하면 고객에게 예약 안내가 열려요. 예약은 자동으로 만들어지지 않고, 고객이 지점·시간을 직접 골라요.' : outcome ? '이 결과로 접수가 종료돼요. 다시 신청하면 새 Case로 접수돼요.' : '검토 결과를 고르면 고객에게 보낼 안내를 확인할 수 있어요.'}</div>
          {openQuestions > 0 && <p className="v17-sf__fine" role="status">고객이 아직 답하지 않은 추가 확인 질문이 {openQuestions}건 있어요. 결과는 등록할 수 있고, 남은 질문 수가 결정 기록에 함께 남아요.</p>}
          {needsException && <div className="v17-sf__exception" role="alert"><strong>확인이 끝나지 않은 서류가 있어요</strong>
            <span>보완 필요 {reviews?.summary.supplement ?? 0}건 · 미확인 {reviews?.summary.unreviewed ?? 0}건이 남아 있어요. 그래도 진행 가능으로 등록하려면 예외 사유를 적어 주세요. 사유와 남은 건수가 결정 이력에 남아요.</span>
            <textarea aria-label="예외 사유" maxLength={500} value={exceptionReason} onChange={event => setExceptionReason(event.target.value)} placeholder="예: 인감증명서 원본은 방문 때 다시 확인하기로 함" />
            <button type="button" className="v17-btn v17-btn--line" onClick={() => openReview()}>서류 확인으로 돌아가기</button></div>}
          <button type="button" className="v17-sf__submit" disabled={!canDecide || (needsException && !exceptionReason.trim())} onClick={() => setConfirming(true)}>결과 등록하고 고객에게 안내</button>
          <span className="v17-sf__fine">등록 전에는 고객에게 아무것도 발송되지 않아요.</span>
        </aside>
      </div>}
      {confirming && <><div className="v17-sf__scrim" onClick={() => setConfirming(false)} /><div className="v17-sf__dialog" role="dialog" aria-modal="true" aria-labelledby="v17-sf-dialog-title">
        <h2 id="v17-sf-dialog-title">이 결과로 등록하고 고객에게 안내할까요?</h2>
        <dl><dt>검토 결과</dt><dd><strong>{outcomeLabel}</strong></dd>{needsException && <><dt>예외 사유</dt><dd>{exceptionReason.trim()} (보완 필요 {reviews?.summary.supplement ?? 0}건 · 미확인 {reviews?.summary.unreviewed ?? 0}건)</dd></>}<dt>안내 문구</dt><dd className="is-pre">{message.trim() || '(없음)'}</dd><dt>대상</dt><dd>{caseNo} · {customer}</dd></dl>
        <p>등록하면 고객에게 웹 알림이 바로 저장되고, 이 Case의 결과는 바꿀 수 없어요.</p>
        <div><button type="button" className="v17-btn v17-btn--ghost" onClick={() => setConfirming(false)}>취소</button><button type="button" className="v17-btn v17-btn--primary" disabled={busy} onClick={() => void decide()}>등록하고 안내</button></div>
      </div></>}
    </main>
  }

  const activeBucket = bucket ?? (BUCKETS.find(entry => derived.counts[entry.key] > 0 && entry.key === 'staff')?.key ?? BUCKETS.find(entry => derived.counts[entry.key] > 0)?.key ?? 'ok')
  const bucketItems = derived.items.filter(item => bucketOf(item) === activeBucket)
  const finalOpen = Boolean(inReview || data.finalDecision)
  return <div className="v17-sd">
    <main className="v17-sd__main">
      <Link className="v17-back" to="/staff/queue"><Chevron />사전검토 업무함</Link>
      {banners}
      <div className="v17-sd__cols">
        <div className="v17-sd__left">
          <section className="v17-card v17-sd__head">
            <div className="v17-sd__title-row">
              <div className="v17-sd__title"><div><span className="v17-code">C-02</span><h1>{TASK_NAME}</h1></div><span><span className="v17-mono">{caseNo}</span> · {customer}{derived.applicant ? ' (신청인)' : ''} · 접수 r{current.revision}</span></div>
              {canStart && <button type="button" className="v17-btn v17-btn--primary v17-btn--lg" onClick={() => void startReview()}>검토 시작</button>}
            </div>
            <div className="v17-sd__tiles">
              <div><span>처리 상태</span><strong className={data.finalDecision ? 'is-ok' : inReview ? 'is-progress' : undefined}>{statusLabel(current.caseStatus)}</strong><span className="v17-mono">{current.caseStatus}</span></div>
              <div><span>AI 사전점검</span><strong className={current.reviewStatus === 'READY_FOR_STAFF' ? 'is-ok' : current.reviewStatus === 'FAILED_NEEDS_STAFF' ? 'is-danger' : undefined}>{statusLabel(current.reviewStatus)}</strong><span className="v17-mono">{current.reviewStatus ?? '—'} · {runLabel(data.precheck?.agentRunStatus)}</span></div>
              <div><span>방문 예약</span><strong>{data.finalDecision?.outcome === 'PROCEED' ? '예약 가능' : '예약 전'}</strong><span>{data.finalDecision?.outcome === 'PROCEED' ? '고객이 지점·시간을 고르면 배정돼요' : '진행 가능 등록·통지 후 고객이 예약'}</span></div>
            </div>
            {submitted && <p className="v17-muted-s">검토를 시작하면 <strong>IN_REVIEW</strong>와 시작 시각이 저장되고, 그 뒤에는 고객이 접수를 취소할 수 없어요. 결과 등록은 검토 시작 후에 할 수 있어요.</p>}
            {inReview && <p className="v17-sd__started">검토를 시작했어요 · 고객 화면에 ‘직원 검토 중’으로 표시되고 접수 취소 버튼이 사라져요.</p>}
          </section>

          <PolicyNoticePanel taskCode="INHERITANCE_DEPOSIT" caseId={current.caseId} />

          <section className="v17-sd__must">
            <div className="v17-sd__must-head"><span className="v17-tag v17-tag--staff">직원</span><h2>직원이 확인해야 할 항목 {derived.summary.length}건</h2><span>AI 통과만으로 생략할 수 없는 항목이에요</span></div>
            <ul>{derived.summary.map(line => <li key={line.title + line.body} className={line.warning ? 'is-warning' : undefined}><span aria-hidden="true">!</span><span><strong>{line.title}</strong><span>{line.body}</span></span></li>)}</ul>
          </section>

          <section className="v17-sd__report">
            <div className="v17-sd__report-head"><div><AiTag /><h2>AI 사전점검 결과서</h2></div><span className="v17-mono">run r{data.precheck?.revision ?? '—'} · {runLabel(data.precheck?.agentRunStatus)} · 항목 {derived.items.length}건</span></div>
            <div className="v17-sd__tabs" role="tablist">{BUCKETS.map(entry => <button type="button" role="tab" key={entry.key} aria-selected={activeBucket === entry.key} className={activeBucket === entry.key ? 'is-on' : undefined} onClick={() => setBucket(entry.key)}>{entry.label} {derived.counts[entry.key]}</button>)}</div>
            {bucketItems.length === 0 ? <p className="v17-sd__empty">이 분류에 해당하는 항목이 없어요.</p>
              : <ul className="v17-sd__items">{bucketItems.map((item, index) => { const v = itemView(item); return <li key={`${item.category}-${index}`} className={`is-${activeBucket}`}>
                <div><strong>{v.title}</strong><span className={`v17-who v17-who--${v.tag === 'AI' ? 'ai' : 'rule'}`}>{v.tag}</span><span className="v17-muted-s">{v.by}</span></div>
                <span>{v.body}</span>
                <span className="v17-sd__ref"><span className="v17-mono">{item.category ?? '-'} / {item.result ?? '-'}</span></span>
              </li> })}</ul>}
            <p className="v17-muted-s">OCR은 출처가 검수된 SolBank 공식 양식(위임장)에만 적용해요. 가족관계증명서·기본증명서 같은 일반 증명서는 OCR 미적용으로, 본문 내용을 자동 추출·검증했다고 표시하지 않아요. Agent는 결과 판정·예약·DB 상태 변경을 하지 않아요.</p>
          </section>

          <section className="v17-card">
            <h2 className="v17-h2 v17-h2--tag">고객 추가 확인 이력 <AiTag /></h2>
            <div className="v17-sd__clarify"><span>아직 고객에게 추가 확인을 요청하지 않았어요.</span></div>
            <span className="v17-muted-s">사실 확인만 요청해요. 권리·자격 판단은 묻지 않아요.</span>
          </section>

          <section className="v17-card">
            <h2 className="v17-h2">기본 정보</h2>
            <dl className="v17-sd__dl">
              <dt>신청인</dt><dd>{derived.applicant ? heirLabel(derived.applicant) : '—'}</dd>
              <dt>피상속인</dt><dd>{data.intake?.decedentName ?? '—'}{data.intake?.deathDate ? ` · 사망일 ${data.intake.deathDate.replaceAll('-', '.')}` : ''}</dd>
              <dt>상속인</dt><dd>{derived.heirs.map(heirLabel).join(', ') || '—'} · {derived.heirs.length}명</dd>
              <dt>미방문</dt><dd>{data.summary?.absentHeirCount ?? 0}명 · 방문 시 확인 서류 {data.summary?.visitOnlyCount ?? 0}건</dd>
            </dl>
          </section>

          {derived.heirs.length > 0 && <section className="v17-card">
            <h2 className="v17-h2">상속인별 방문 계획</h2>
            <div className="v17-scroll"><table className="v17-table v17-table--plan"><thead><tr><th>상속인</th><th>고객 입력</th><th>AI 사전점검</th><th>직원 확인</th></tr></thead>
              <tbody>{derived.heirs.map(heir => { const unconfirmed = heir.visitStatus === 'UNCONFIRMED_ABSENT'; const written = heir.visitStatus !== 'ATTENDING' && heir.delegationMethod === 'DOCUMENT'; return <tr key={heir.personRef} className={unconfirmed ? 'is-warn' : undefined}>
                <td>{heirLabel(heir)}</td><td>{visitInput(heir)}</td>
                <td>{unconfirmed ? <span className="v17-chip v17-chip--warn-line">잠정 불참 · UNCONFIRMED_ABSENT</span> : written ? '위임서류 우선 확인' : '특이사항 없음'}</td>
                <td className={unconfirmed ? 'is-strong' : 'is-muted'}>{unconfirmed ? '확정 불참 아님 · 결과 안내 시 불참 기준 위임서류 함께 안내' : written ? '위임장·인감 원본, 실제 위임 의사' : '방문 시 원본'}</td>
              </tr> })}</tbody></table></div>
          </section>}

          <section className="v17-card">
            <div className="v17-sd__docs-head"><h2 className="v17-h2">제출 서류 {derived.submittedFiles.length}건 <span className="v17-muted-s">직원 확인 {judgedOk}건 · 보완 필요 {judgedSup}건 · 미확인 {remaining}건{reviews?.reviewerName ? ` · 검토자 ${reviews.reviewerName}` : ' · 검토자 미배정'}</span></h2><button type="button" className="v17-btn v17-btn--line" onClick={() => openReview()}>서류 확인하기</button></div>
            <div className="v17-scroll"><table className="v17-table v17-table--docs"><thead><tr><th>서류 · 파일 버전</th><th>제출자</th><th>OCR</th><th>시스템 확인</th><th>문서 상태</th><th>직원 판단</th></tr></thead>
              <tbody>{derived.submittedFiles.map(({ slot, file }) => { const poa = slot.documentTypeCode === POWER_OF_ATTORNEY; const mark = reviewOf(file.documentFileId); return <tr key={file.documentFileId}>
                <td><span className="v17-sd__doc"><strong>{c02DocumentLabel(slot.documentTypeCode)}</strong><span className="v17-mono" title={file.fileName}>{file.fileName} · v{file.version}</span></span></td>
                <td>{slot.personRef ? derived.byRef.get(slot.personRef)?.name ?? '상속인' : '공통'}</td>
                <td><span className={`v17-chip ${poa ? 'v17-chip--info' : 'v17-chip--muted'}`}>{poa ? ocrStatusLabel(file, ocr) : 'OCR 미적용'}</span></td>
                <td className="is-sub">종류·형식·용량</td>
                <td className="v17-mono">{file.status}</td>
                <td className={mark?.result === 'CONFIRMED' ? 'is-ok' : mark?.result === 'SUPPLEMENT_REQUIRED' ? 'is-caution' : 'is-muted'}>{mark?.result === 'CONFIRMED' ? '확인 완료' : mark?.result === 'SUPPLEMENT_REQUIRED' ? '보완 필요' : '미확인'}{mark && <small className="v17-sd__who">{mark.staffName ?? '직원'} · {formatDateTime(mark.reviewedAt)}</small>}</td>
              </tr> })}</tbody></table></div>
          </section>

          <C02StaffCaseQa caseId={current.caseId} canAsk={Boolean(submitted && !data.finalDecision)} onChanged={() => void load()} />

          <div className="v17-sd__pair">
            <section className="v17-card">
              <h2 className="v17-h2">처리 이력</h2>
              {timeline.length === 0 ? <span className="v17-muted">기록된 처리 이력이 없어요.</span> : <ol className="v17-sd__history">{timeline.map((entry, index) => <li key={index}><span className="v17-mono">{formatDateTime(entry.at).slice(5)}</span><span>{TIMELINE_LABEL[entry.kind] ?? entry.kind}{entry.actorName ? ` · ${entry.actorName}` : ''}{entry.summary ? ` · ${entry.summary}` : ''}{entry.detail && !entry.kind.startsWith('AGENT_RUN') ? ` · ${entry.detail}` : ''}</span></li>)}</ol>}
            </section>
          </div>

          <div className="v17-sd__bar">
            <span>{data.finalDecision ? '검토 결과가 등록된 Case예요.' : inReview ? '서류를 확인하고 결과를 등록하세요. 결과는 직원이 확정한 문구로만 고객에게 안내돼요.' : '검토를 시작한 뒤 서류를 확인하고 결과를 등록할 수 있어요.'}</span>
            <button type="button" className="v17-btn v17-btn--line v17-btn--lg" onClick={() => openReview()}>서류 확인</button>
            <button type="button" className="v17-btn v17-btn--primary v17-btn--lg" disabled={!finalOpen} onClick={openFinal}>검토 결과 등록</button>
          </div>

          <section className="v17-extra" aria-label="기존 화면 요소">
            <h2 className="v17-h2">추가 정보</h2>
            <div className="v17-extra__row"><span>Case #{current.caseId}</span><span>업무 번호 #{current.workItemId}</span><span>접수 버전 r{current.revision}</span>
              <button type="button" className="v17-btn v17-btn--line" disabled={!taskBffEnabled || busy} onClick={() => void load()}>{busy ? '처리 중…' : '최신 Case 조회'}</button>
              <button type="button" className="v17-btn v17-btn--line" disabled={!canPrecheck} onClick={() => void precheck()}>사전점검 실행</button></div>
          </section>
        </div>
        <C02AiReviewPanel caseId={current.caseId} revision={current.revision} canGenerate={Boolean(!data.finalDecision && (submitted || inReview))} reviewStarted={inReview}
          counts={derived.tally} candidates={derived.candidates} steps={steps}
          evidence={derived.submittedFiles.map(row => ({ id: row.file.documentFileId, name: row.file.fileName }))} onEvidence={openReview} />
      </div>
    </main>
  </div>
}

function Chevron() {
  return <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path d="M15 6l-6 6 6 6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
}
