import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02CustomerCaseSnapshot, type C02Heir } from '../api/taskApi'
import { ApiError } from '../api/client'
import { toUserMessageOr } from '../api/errorMessages'
import { c02DocumentGuidance, c02DocumentLabel, c02RequirementLabel, c02OutOfScopeLabel, c02ValidationLabel } from '../ui/customerLabels'
import { formatDate, formatDateTime, groupDocuments, heirCaption, lastActivityAt, uploadProgress, visitLabel, type DocumentRow } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'

type RequiredDocument = { documentTypeCode: string; personRef?: string | null; requirement: string; note: string }
type PrepareData = { requiredDocuments?: RequiredDocument[]; submission?: { submittable: boolean; errors: string[]; outOfScope: string[]; missingDocuments: string[] }; caseView?: { caseId: number; revision: number } }
type HeirForm = Omit<C02Heir, 'birthDate' | 'delegationReceiptNo' | 'higherOrderOrSubrogation'> & { birthDate: string; delegationReceiptNo: string }
const emptyHeir = (applicant = false): HeirForm => ({ personRef: crypto.randomUUID(), name: '', birthDate: '', relationship: '', applicant, visitStatus: 'ATTENDING', delegationMethod: 'NONE', delegationReceiptNo: '' })
const MAX_BYTES = 10 * 1024 * 1024
// Unsubmitted drafts are reset 72 hours after the last activity (team decision "3일").
const DRAFT_TTL_MS = 72 * 60 * 60 * 1000

type SlotCheck = { tone: 'ok' | 'error' | 'running'; text: string }
type PoaPreparation = 'EXISTING' | 'SUPPORT' | 'UNDECIDED'
export const shouldShowPoaPreparation = (visitStatus: string, delegationMethod: string) => visitStatus === 'ABSENT' && delegationMethod === 'DOCUMENT'
const MIME_LABEL: Record<string, string> = { 'application/pdf': 'PDF', 'image/png': 'PNG', 'image/jpeg': 'JPG' }
const sizeLabel = (bytes: number) => bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(bytes / 1024))}KB`
/** What Spring recorded for a stored file: detected type, size and the version kept for this slot. */
const storedCheck = (document: NonNullable<DocumentRow['uploaded']>): SlotCheck => ({ tone: 'ok', text: `파일 형식 확인 · ${MIME_LABEL[document.mimeType] ?? document.mimeType} · ${sizeLabel(document.sizeBytes)} · ${document.version}번째 제출본 · 내용은 담당 직원이 확인해요` })

export function PowerOfAttorneyPreparationQuestion({ value, onChange }: { value?: PoaPreparation; onChange: (value: PoaPreparation) => void }) {
  return <div className="c02-notice" data-testid="poa-preparation-question"><strong>위임장을 이미 준비하셨나요?</strong><div role="radiogroup" className="c02-choice-group">
    <button type="button" className={value === 'EXISTING' ? 'is-selected' : ''} onClick={() => onChange('EXISTING')}>이미 작성한 위임장이 있어요</button>
    <button type="button" className={value === 'SUPPORT' ? 'is-selected' : ''} onClick={() => onChange('SUPPORT')}>위임장 작성 지원이 필요해요</button>
    <button type="button" className={value === 'UNDECIDED' ? 'is-selected' : ''} onClick={() => onChange('UNDECIDED')}>아직 결정하지 않았어요</button>
  </div>
  {value === 'EXISTING' && <p>필수서류 단계에서 기존 위임장 업로드 칸에 서명·날인된 파일을 올려 주세요.</p>}
  {value === 'SUPPORT' && <p>필수서류를 확인한 뒤 위임장 작성 지원에서 PDF 초안을 만들 수 있어요.</p>}
  {value === 'UNDECIDED' && <p>필수서류 단계에서 준비 방식을 다시 선택할 수 있어요.</p>}
  </div>
}

function DocumentLine({ row, disabled, check, onFile }: { row: DocumentRow; disabled: boolean; check?: SlotCheck; onFile: (row: DocumentRow, file?: File) => void }) {
  const sub = row.uploaded ? `${row.uploaded.fileName} · ${formatDateTime(row.uploaded.uploadedAt)}` : c02DocumentGuidance(row.slot.documentTypeCode, row.slot.note)
  const shown = check ?? (row.uploaded ? storedCheck(row.uploaded) : undefined)
  return <div className={`c02-document${shown?.tone === 'error' ? ' is-rejected' : ''}`}>
    <div><strong>{row.title}</strong><span>{sub}</span>{shown && <em className={`c02-check c02-check--${shown.tone}`} role={shown.tone === 'error' ? 'alert' : undefined}>{shown.tone === 'ok' ? '✓ ' : shown.tone === 'error' ? '✕ ' : ''}{shown.text}</em>}</div>
    <b className={row.slot.requirement === 'REQUIRED' ? '' : 'is-optional'}>{row.slot.requirement === 'REQUIRED' ? '필수' : '해당 시'}</b>
    {row.uploaded && <b className="is-uploaded">업로드됨</b>}
    <label className={`c02-file-button${disabled ? ' is-disabled' : ''}`}>{row.uploaded ? '파일 교체' : '파일 선택'}
      <input type="file" hidden accept=".pdf,.jpg,.jpeg,.png" disabled={disabled} aria-label={`${row.title} 파일 ${row.uploaded ? '교체' : '선택'}`} onChange={event => { onFile(row, event.currentTarget.files?.[0]); event.currentTarget.value = '' }} />
    </label>
  </div>
}

function PowerOfAttorneySupport({ caseId, row, intake, preparation }: { caseId: number; row: DocumentRow; intake: C02CustomerCaseSnapshot['intake']; preparation?: PoaPreparation }) {
  const [open, setOpen] = useState(preparation === 'SUPPORT'); const [account, setAccount] = useState(''); const [product, setProduct] = useState(''); const [agentAddress, setAgentAddress] = useState(''); const [address, setAddress] = useState(''); const [relation, setRelation] = useState(''); const [date, setDate] = useState(new Date().toISOString().slice(0, 10)); const [url, setUrl] = useState<string | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  useEffect(() => { if (preparation === 'SUPPORT') setOpen(true) }, [preparation])
  const personRef = row.slot.personRef; const heir = intake?.heirs.find(item => item.personRef === personRef)
  if (row.slot.documentTypeCode !== 'ABSENT_HEIR_POWER_OF_ATTORNEY' || !personRef || !heir) return null
  const create = async () => { setBusy(true); setError(null); if (url) URL.revokeObjectURL(url); try { const blob = await c02Api.powerOfAttorneyDraft(caseId, { delegatorPersonRef: personRef, accountNumbers: account.split(/[,\s]+/).filter(Boolean), depositProduct: product, agentAddress, delegatorAddress: address, delegatorRelationship: relation || heir.relationship, writtenDate: date || null }); setUrl(URL.createObjectURL(blob)) } catch (cause) { setError(toUserMessageOr(cause, '위임장 초안을 만들지 못했어요.')) } finally { setBusy(false) } }
  return <div className="c02-notice"><strong>위임장 준비 방법</strong><p>이미 작성하고 서명·날인한 위임장이 있다면 위의 파일 선택으로 바로 업로드하세요.</p><button type="button" className="c02-secondary" onClick={() => setOpen(value => !value)}>위임장 작성 지원</button>{open && <div className="c02-fields c02-fields--two"><p>자동 입력: 예금주 {intake?.decedentName || '미입력'} · 대리인 {intake?.heirs.find(item => item.visitStatus === 'ATTENDING')?.name || '미입력'} · 위임인 {heir.name}</p><label>계좌번호<input value={account} onChange={event => setAccount(event.target.value)} placeholder="최대 3건, 쉼표로 구분" /></label><label>예금과목<input value={product} onChange={event => setProduct(event.target.value)} /></label><label>대리인 주소<input value={agentAddress} onChange={event => setAgentAddress(event.target.value)} /></label><label>위임인 주소<input value={address} onChange={event => setAddress(event.target.value)} /></label><label>관계<input value={relation} onChange={event => setRelation(event.target.value)} placeholder={heir.relationship || '추가 입력'} /></label><label>작성일<input type="date" value={date} onChange={event => setDate(event.target.value)} /></label><button type="button" disabled={busy} onClick={() => void create()}>{busy ? 'PDF 생성 중' : 'PDF 미리보기 생성'}</button>{error && <p className="c02-alert">{error}</p>}{url && <><iframe title="위임장 PDF 미리보기" src={url} style={{ width: '100%', minHeight: 420, border: 0 }} /><a className="c02-primary" href={url} download="inheritance-power-of-attorney-draft.pdf">PDF 다운로드</a><p>위임인이 직접 서명·날인한 뒤 같은 위임장 슬롯에 업로드해 주세요. 초안 생성만으로 제출되지 않습니다.</p></>}</div>}</div>
}

const VISIT_CHOICES: { label: string; match: (heir: { visitStatus: string; delegationMethod: string }) => boolean }[] = [
  { label: '방문 예정', match: heir => heir.visitStatus === 'ATTENDING' },
  { label: '불참 · 서류로 위임', match: heir => heir.visitStatus !== 'ATTENDING' && heir.delegationMethod === 'DOCUMENT' },
  { label: '불참 · SolBank 앱 위임장', match: heir => heir.visitStatus !== 'ATTENDING' && heir.delegationMethod === 'RECEIPT' },
]

/** After the Case exists: upload per-slot documents, then hand over to the signature/SMS page. */
function C02CasePanel({ caseId }: { caseId: number }) {
  const navigate = useNavigate()
  const [snapshot, setSnapshot] = useState<C02CustomerCaseSnapshot | null>(null)
  const [poaPreparations, setPoaPreparations] = useState<Record<string, PoaPreparation>>({})
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [checks, setChecks] = useState<Record<string, SlotCheck>>({})
  const [message, setMessage] = useState<string | null>(null); const [error, setError] = useState<string | null>(null)
  const load = useCallback(async () => {
    try { const response = await c02Api.snapshot(caseId, `c02-snapshot-${Date.now()}`); setSnapshot(response.data) }
    catch (cause) { setError(toUserMessageOr(cause, '접수 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) }
  }, [caseId])
  useEffect(() => { if (taskBffEnabled) void load() }, [load])
  useEffect(() => {
    const key = snapshot?.workItemId ? `c02-poa-preparation-${snapshot.workItemId}` : null
    if (!key) return
    try { setPoaPreparations(JSON.parse(sessionStorage.getItem(key) ?? '{}') as Record<string, PoaPreparation>) } catch { setPoaPreparations({}) }
  }, [snapshot?.workItemId])
  const setPoaPreparation = (personRef: string, value: PoaPreparation) => {
    setPoaPreparations(current => {
      const next = { ...current, [personRef]: value }
      if (snapshot?.workItemId) sessionStorage.setItem(`c02-poa-preparation-${snapshot.workItemId}`, JSON.stringify(next))
      return next
    })
  }
  // Each upload revises the Case on the server, so the next call always uses the reloaded revision.
  const upload = async (row: DocumentRow, file?: File) => {
    if (!snapshot || !file) return
    // Only the size is checked here (to avoid sending a huge file). Type, real file content and slot rules
    // are left to Spring so its verdict is what the customer sees on the row.
    if (file.size > MAX_BYTES) { setChecks(current => ({ ...current, [row.key]: { tone: 'error', text: '파일은 10MB 이하로 올려 주세요.' } })); return }
    setBusyKey(row.key); setError(null); setMessage(null)
    setChecks(current => ({ ...current, [row.key]: { tone: 'running', text: `${file.name} 확인 중…` } }))
    try {
      const response = await c02Api.uploadDocument(caseId, { revision: snapshot.revision, requestId: `c02-upload-${caseId}-${crypto.randomUUID()}`, documentSlotKey: row.key, file })
      const stored = response.data?.document
      setChecks(current => ({ ...current, [row.key]: stored ? storedCheck(stored) : { tone: 'ok', text: '파일 형식 확인 · 내용은 담당 직원이 확인해요' } }))
      await load(); setMessage(`${row.title} 파일을 서버가 확인하고 저장했어요.`)
    } catch (cause) {
      setChecks(current => ({ ...current, [row.key]: { tone: 'error', text: `서버가 거부했어요: ${toUserMessageOr(cause, '파일을 올리지 못했어요. 잠시 후 다시 시도해 주세요.')}` } }))
    } finally { setBusyKey(null) }
  }
  if (!snapshot) return <div className="c02-page"><main className="c02-page__main">{error ? <p className="c02-alert" role="alert">{error}</p> : <p className="c02-notice" role="status">접수 정보를 불러오고 있어요.</p>}</main></div>
  if (snapshot.caseStatus !== 'DRAFT') return <Navigate to={`/tasks/c02/cases/${caseId}`} replace />
  const intake = snapshot.intake
  const heirs = intake?.heirs ?? []
  const groups = groupDocuments(snapshot.requiredDocuments, snapshot.documents, intake)
  const progress = uploadProgress(groups)
  const lastActivity = lastActivityAt(snapshot)
  const resetAt = lastActivity ? new Date(new Date(lastActivity).getTime() + DRAFT_TTL_MS).toISOString() : null
  const ready = progress.missingRequired === 0
  const busy = busyKey !== null
  const uploadable = [...groups.common, ...groups.byHeir.flatMap(group => group.rows)]
  return <div className="c02-page"><main className="c02-page__main">
    <header className="c02-page__heading"><div><div className="c02-page__badges"><span>C · 직원 검토</span><span>방문 전 서류 사전확인</span><span>작성 중</span></div><h1>상속예금 해지 및 지급 사전접수</h1></div><button type="button" className="c02-page__hold" onClick={() => navigate('/chat')}>보류하고 채팅으로</button><button type="button" className="c02-secondary" data-testid="c02-edit-intake" onClick={() => navigate(`/tasks/c02/${snapshot.workItemId}?edit=1`)}>신청정보 수정</button></header>
    {error && <p className="c02-alert" role="alert">{error}</p>}{message && <p className="c02-notice" role="status">{message}</p>}
    <div className="c02-progress"><strong>접수 준비 현황</strong><span>필수정보 <b>입력 완료</b></span><span>필수 서류 <b>{progress.required - progress.missingRequired}/{progress.required}</b></span><span>업로드 서류 <b>{uploadable.filter(row => row.uploaded).length}/{uploadable.length}</b></span>{resetAt && <em>{formatDateTime(resetAt)}까지 진행하지 않으면 입력 내용 초기화</em>}</div>
    <section className="c02-card"><h2>1. 돌아가신 분(피상속인) 정보</h2><dl className="c02-summary"><div><dt>성명</dt><dd>{intake?.decedentName ?? '—'}</dd></div><div><dt>생년월일</dt><dd>{formatDate(intake?.decedentBirthDate)}</dd></div><div><dt>사망일</dt><dd>{formatDate(intake?.deathDate)}</dd></div></dl>{intake?.deathDate && intake.deathDate > '2007-12-31' && <p className="v17-c02-ok">✓ 사망일이 2007.12.31 이후라 사전접수 대상이에요 · 이전이면 고객상담센터로 안내해요</p>}</section>
    <section className="c02-card"><h2>2. 신청인과 상속인 정보</h2><dl className="c02-summary v17-c02-two"><div><dt>신청인{heirs.find(heir => heir.applicant) ? `(${heirs.find(heir => heir.applicant)?.name})` : ''}과 돌아가신 분의 관계</dt><dd>{heirs.find(heir => heir.applicant)?.relationship || '—'}</dd></div><div><dt>상속 순위</dt><dd>1·2순위 (배우자·자녀·부모)</dd></div></dl><strong className="c02-card__label">상속인 {heirs.length}명</strong>
      <div className="c02-table-wrap"><table className="c02-table"><thead><tr><th>상속인</th><th>관계</th><th>비고</th></tr></thead><tbody>{heirs.map(heir => <tr key={heir.personRef}><td>{heir.name}</td><td>{heir.relationship || '—'}</td><td>{heir.applicant ? <b className="c02-accent">신청인</b> : '—'}</td></tr>)}</tbody></table></div></section>
    <section className="c02-card"><div><h2>3. 상속인별 방문·위임 방식</h2><p className="c02-subtitle">입력하신 방문 계획은 접수 후 직원이 확인해요. AI 확인 질문에 답하지 않으시면, 준비서류 안내를 위해 ‘잠정 불참·미확인’으로 표시돼요.</p></div>
      {heirs.map(heir => <div className="c02-visit-row" key={heir.personRef}><strong>{heir.name} <small>{heirCaption(heir)}</small></strong><div role="group" aria-label={`${heir.name} 방문 방식`} className="c02-choice-group">{VISIT_CHOICES.map(choice => { const on = choice.match(heir); return <button type="button" key={choice.label} className={on ? 'is-selected' : ''} aria-pressed={on} onClick={() => { if (!on) navigate(`/tasks/c02/${snapshot.workItemId}?edit=1`) }}>{choice.label}</button> })}</div>{!VISIT_CHOICES.some(choice => choice.match(heir)) && <span className="c02-chip is-selected">{visitLabel(heir)}</span>}</div>)}
      <p className="c02-helper c02-helper--muted">방문 방식을 바꾸면 신청정보 수정 화면으로 이동해요. 바꾼 내용에 맞게 필요서류가 다시 정해져요.</p></section>
    <section className="c02-card"><div className="c02-card__title"><h2>4. 제출자별 필요서류</h2><span className="c02-source">근거: C02-01 상속예금 FAQ – 필요서류</span></div>
      <p className="c02-notice v17-c02-info">이 목록은 3번의 방문·위임 방식에 따라 서버 규칙이 정한 서류예요. 올린 파일은 서버가 확장자·실제 파일 형식·용량·서류 항목을 확인한 뒤 저장해요. 가족관계증명서 같은 일반 증명서는 내용을 자동으로 읽지 않고, 원문은 직원이 확인해요.</p>
      {groups.common.length > 0 && <><strong className="c02-card__label">돌아가신 분 관련 서류</strong><div className="c02-documents">{groups.common.map(row => <DocumentLine key={row.key} row={row} disabled={busy} check={checks[row.key]} onFile={upload} />)}</div></>}
      {groups.byHeir.length > 0 && <><strong className="c02-card__label">방문 상속인별 서류</strong><div className="c02-documents">{groups.byHeir.flatMap(group => group.rows).map(row => { const personRef = row.slot.personRef ?? ''; const preparation = poaPreparations[personRef]; return <Fragment key={row.key}><DocumentLine row={row} disabled={busy} check={checks[row.key]} onFile={upload} />{row.slot.documentTypeCode === 'ABSENT_HEIR_POWER_OF_ATTORNEY' && <div className="c02-document__attached">{(!preparation || preparation === 'UNDECIDED') && <PowerOfAttorneyPreparationQuestion value={preparation} onChange={value => setPoaPreparation(personRef, value)} />}<PowerOfAttorneySupport caseId={caseId} row={row} intake={intake} preparation={preparation} /></div>}</Fragment> })}</div></>}
      <p className="c02-helper c02-helper--muted">PDF · JPG · PNG, 파일당 10MB 이하 · 서버 기준 필수 서류 {progress.required - progress.missingRequired}/{progress.required}건 확인 · 사본 표시가 없는 서류는 방문 시 3개월 이내 발급 원본을 가져와 주세요.</p></section>
    <section className="c02-card"><h2>5. 방문할 때 가져올 것</h2><ul className="c02-bring">{groups.visitOnly.map(row => <li key={row.key}><span>{row.title}</span><b>방문 시 확인</b></li>)}<li><span>업로드한 서류의 원본 (사본 가능 서류 제외)</span><b>방문 시 필수</b></li></ul></section>
    <section className="c02-info"><strong>접수 전에 알아두세요</strong><span>· 방문 영업점과 시간은 지금 고르지 않아요. 직원 검토 결과가 <b>‘진행 가능’</b>이면 알림을 받은 뒤 예약해요.</span><span>· 예금 합계와 방문 인원에 따라 일부 상속인만으로 가능한지, 상속인 관계·위임서류가 맞는지는 직원이 판단해요.</span><span>· 직원이 검토를 시작하기 전까지는 접수를 취소할 수 있어요.</span></section>
  </main><footer className="c02-footer"><div><span>{ready ? '필수 서류가 준비됐어요. 신청인이 요청서·동의서에 서명하고 문자 인증을 마치면 사전검토가 요청돼요. 방문 상속인 서명은 영업점에서 받아요.' : `필수 서류 ${progress.missingRequired}건을 더 올려야 서명할 수 있어요.`}</span><button type="button" className="c02-secondary" disabled={busy} onClick={() => setMessage('입력 내용과 올린 서류는 서버에 저장돼 있어요. 업무 화면에서 이어서 진행할 수 있어요.')}>임시저장</button><button type="button" className="c02-primary" disabled={!ready || busy} onClick={() => navigate(`/tasks/c02/cases/${caseId}/sign`)}>서명·인증하고 사전검토 요청</button></div></footer></div>
}

/** C-02 preparation sends no customer identity from the browser; the BFF derives it from its session. */
export function C02IntakePage() {
  const { workItemId } = useParams()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // The router owns this value.  Do not infer it from a Case id or client form state.
  const parsedWorkItemId = workItemId && /^\d+$/.test(workItemId) ? Number(workItemId) : null
  const editDraft = searchParams.get('edit') === '1'
  const poaPreparationKey = parsedWorkItemId ? `c02-poa-preparation-${parsedWorkItemId}` : null
  const [deathDate, setDeathDate] = useState(''); const [birthDate, setBirthDate] = useState(''); const [name, setName] = useState('')
  const [heirs, setHeirs] = useState<HeirForm[]>([emptyHeir(true)])
  const prepareRequest = useRef<{ fingerprint: string; requestId: string } | null>(null)
  const [poaPreparations, setPoaPreparations] = useState<Record<string, PoaPreparation>>(() => {
    if (!poaPreparationKey) return {}
    try { return JSON.parse(sessionStorage.getItem(poaPreparationKey) ?? '{}') as Record<string, PoaPreparation> } catch { return {} }
  })
  const [result, setResult] = useState<PrepareData | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  useEffect(() => { if (poaPreparationKey) sessionStorage.setItem(poaPreparationKey, JSON.stringify(poaPreparations)) }, [poaPreparationKey, poaPreparations])
  useEffect(() => {
    const item=parsedWorkItemId
    if (!taskBffEnabled || item === null || !Number.isSafeInteger(item) || item<=0) return
    let active=true
    c02Api.resume(item, `c02-resume-${item}`).then(async response => {
      if (!active || !response.data) return
      if (!editDraft || !response.data.caseView?.caseId) { setResult(response.data); return }
      const snapshot = await c02Api.snapshot(response.data.caseView.caseId, `c02-edit-snapshot-${item}`)
      if (!active || !snapshot.data?.intake) return
      const intake = snapshot.data.intake
      setName(intake.decedentName ?? '')
      setBirthDate(intake.decedentBirthDate ?? '')
      setDeathDate(intake.deathDate ?? '')
      setHeirs(intake.heirs.map(heir => ({ personRef: heir.personRef, name: heir.name, birthDate: heir.birthDate ?? '', relationship: heir.relationship, applicant: heir.applicant, visitStatus: heir.visitStatus as HeirForm['visitStatus'], delegationMethod: heir.delegationMethod as HeirForm['delegationMethod'], delegationReceiptNo: '' })))
      setResult(null)
    })
      .catch(cause => {
        if (!active || (cause instanceof Error && cause.message.includes('No C-02 case'))) return
        // A submitted/rejected Case cannot be resumed as a draft.  Do not leave
        // its WorkItem on the intake form; send the customer to their owned list.
        if (cause instanceof ApiError && cause.code === 'INVALID_STATE') { navigate('/tasks/c02/cases', { replace: true }); return }
        setError(toUserMessageOr(cause, '기존 접수 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'))
      })
    return () => { active=false }
  }, [parsedWorkItemId, navigate, editDraft])
  const update = (index: number, patch: Partial<HeirForm>) => setHeirs(items => items.map((item, i) => i === index ? { ...item, ...patch } : item))
  const setPoaPreparation = (personRef: string, value: PoaPreparation) => setPoaPreparations(current => ({ ...current, [personRef]: value }))
  const validate = async () => {
    const item = parsedWorkItemId
    const decedentName = name.trim()
    const decedentBirthDate = birthDate.trim()
    const decedentDeathDate = deathDate.trim()
    if (item === null || !Number.isSafeInteger(item) || !item || !decedentName || !decedentDeathDate) { setError('업무 ID와 피상속인 정보를 확인해 주세요.'); return }
    const preparation = { workItemId: item, registeredPhone: null, decedent: { name: decedentName, birthDate: decedentBirthDate || null, deathDate: decedentDeathDate, depositTotal: 0 }, heirs: heirs.map(h => ({ ...h, birthDate: h.birthDate || null, delegationReceiptNo: h.delegationReceiptNo || null, higherOrderOrSubrogation: false })), documentStatuses: {} }
    const fingerprint = JSON.stringify(preparation)
    const storedKey = `c02-prepare-request-${item}`
    let requestId = prepareRequest.current?.fingerprint === fingerprint ? prepareRequest.current.requestId : null
    if (!requestId) {
      try { const stored = JSON.parse(sessionStorage.getItem(storedKey) ?? 'null') as { fingerprint?: string; requestId?: string } | null; if (stored?.fingerprint === fingerprint && stored.requestId) requestId = stored.requestId } catch { /* a malformed browser cache must not block preparation */ }
    }
    if (!requestId) requestId = `c02-prepare-${item}-${crypto.randomUUID()}`
    prepareRequest.current = { fingerprint, requestId }
    try { sessionStorage.setItem(storedKey, JSON.stringify(prepareRequest.current)) } catch { /* request id remains valid for this mounted page */ }
    setBusy(true); setError(null)
    try {
      const response = await c02Api.prepare({ requestId, ...preparation })
      setResult((response.data ?? null) as PrepareData | null)
    } catch (e) { setError(toUserMessageOr(e, '입력 내용을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }
  const submission = result?.submission
  if (result?.caseView?.caseId) return <C02CasePanel caseId={result.caseView.caseId} />
  return <div className="c02-page"><main className="c02-page__main">
    <header className="c02-page__heading"><div><div className="c02-page__badges"><span>C · 직원 검토</span><span>방문 전 서류 사전확인</span><span>작성 중</span></div><h1>상속예금 해지 및 지급 사전접수</h1></div><span className="c02-page__hold">보류하고 채팅으로</span></header>
    {!taskBffEnabled && <p className="c02-notice" role="status">로그인 BFF 연결을 기다리고 있습니다. 세션 없이 Case를 만들거나 고객 ID를 임의로 전송하지 않습니다.</p>}{error && <p className="c02-alert" role="alert">{error}</p>}
    <div className="c02-progress"><strong>접수 준비 현황</strong><span>필수정보 <b>{name && deathDate ? '완료' : '입력 필요'}</b></span><span>상속인 <b>{heirs.length}명</b></span></div>
    <section className="c02-card"><h2>1. 돌아가신 분(피상속인) 정보</h2><div className="c02-fields c02-fields--three"><label>성명<input value={name} onChange={e => setName(e.target.value)} /></label><label>생년월일<input type="date" value={birthDate} onChange={e => setBirthDate(e.target.value)} /></label><label>사망일<input type="date" value={deathDate} onChange={e => setDeathDate(e.target.value)} /></label></div><p className="c02-helper">사망일과 권리관계는 직원이 원본으로 직접 확인합니다. 사전검토 결과가 자동 승인을 뜻하지 않습니다.</p></section>
    <section className="c02-card"><h2>2. 신청인과 상속인 정보</h2><div className="c02-fields c02-fields--two"><label>신청인과 돌아가신 분의 관계<select value={heirs.find(h => h.applicant)?.relationship ?? ''} onChange={e => { const i = heirs.findIndex(h => h.applicant); if (i >= 0) update(i, { relationship: e.target.value }) }}><option value="">선택해 주세요</option><option value="자녀">자녀</option><option value="배우자">배우자</option><option value="부모">부모</option><option value="형제자매">형제자매</option><option value="기타">기타</option></select></label><label>상속 순위<select disabled><option>1·2순위 (배우자·자녀·부모)</option></select></label></div><div className="c02-heirs"><div className="c02-heirs__head"><strong>상속인 {heirs.length}명</strong><button className="c02-text-button" type="button" onClick={() => setHeirs(xs => [...xs, emptyHeir()])}>상속인 추가</button></div>{heirs.map((h, i) => <article className="c02-heir" key={h.personRef}><div className="c02-heir__fields"><label>성명<input aria-label={`상속인 ${i + 1} 성명`} value={h.name} onChange={e => update(i, { name: e.target.value })} /></label><label>관계<input value={h.relationship} onChange={e => update(i, { relationship: e.target.value })} /></label><label>생년월일<input type="date" value={h.birthDate} onChange={e => update(i, { birthDate: e.target.value })} /></label></div><div className="c02-heir__actions"><label className="c02-applicant"><input type="radio" checked={h.applicant} onChange={() => setHeirs(xs => xs.map((x, n) => ({ ...x, applicant: n === i })))} /> 신청인</label>{heirs.length > 1 && <button className="c02-text-button" type="button" onClick={() => setHeirs(xs => xs.filter((_, n) => n !== i))}>삭제</button>}</div></article>)}</div></section>
    <section className="c02-card"><div><h2>3. 상속인별 방문·위임 방식</h2><p className="c02-subtitle">원칙은 상속인 전원 방문입니다. 오지 못하는 분은 위임 방식을 선택해 주세요.</p></div>{heirs.map((h, i) => <div className="c02-visit-row" key={h.personRef}><strong>{h.name || `상속인 ${i + 1}`} <small>{h.relationship || '관계 미입력'}{h.applicant ? ' · 신청인' : ''}</small></strong><div role="radiogroup" className="c02-choice-group"><button type="button" className={h.visitStatus === 'ATTENDING' ? 'is-selected' : ''} onClick={() => update(i, { visitStatus: 'ATTENDING', delegationMethod: 'NONE' })}>방문 예정</button><button type="button" className={h.visitStatus === 'ABSENT' && h.delegationMethod === 'DOCUMENT' ? 'is-selected' : ''} onClick={() => update(i, { visitStatus: 'ABSENT', delegationMethod: 'DOCUMENT', delegationReceiptNo: '' })}>불참 · 서류로 위임</button><button type="button" className={h.visitStatus === 'ABSENT' && h.delegationMethod === 'RECEIPT' ? 'is-selected' : ''} onClick={() => update(i, { visitStatus: 'ABSENT', delegationMethod: 'RECEIPT' })}>불참 · SolBank 앱 위임장</button></div>{h.visitStatus === 'ABSENT' && h.delegationMethod === 'DOCUMENT' && <PowerOfAttorneyPreparationQuestion value={poaPreparations[h.personRef]} onChange={value => setPoaPreparation(h.personRef, value)} />}{h.delegationMethod === 'RECEIPT' && <label className="c02-receipt">앱 위임장 접수번호<input value={h.delegationReceiptNo} onChange={e => update(i, { delegationReceiptNo: e.target.value })} /></label>}</div>)}<p className="c02-helper c02-helper--muted">입력하신 방문 계획은 접수 후 직원이 확인해요. AI가 확인 질문을 드렸는데 답하지 않으시면, 준비서류 안내를 위해 ‘잠정 불참·미확인’으로 표시돼요.</p></section>
    {submission && <section className="c02-card"><h2>4. 제출 전 확인할 서류</h2><p className="c02-notice">일반 증명서는 자동 판독하지 않습니다. 서류 종류와 제출 여부만 확인하며 원본은 직원이 확인합니다.</p>{submission.outOfScope.length > 0 && <p className="c02-alert" role="alert">{submission.outOfScope.map(c02OutOfScopeLabel).join(' ')}</p>}{submission.errors.length > 0 && <p className="c02-alert" role="alert">{submission.errors.map(c02ValidationLabel).join(' ')}</p>}<div className="c02-documents">{result?.requiredDocuments?.map(d => <div className="c02-document" key={`${d.documentTypeCode}-${d.personRef ?? 'common'}`}><div><strong>{c02DocumentLabel(d.documentTypeCode)}</strong><span>{c02DocumentGuidance(d.documentTypeCode, d.note)}</span></div><b>{c02RequirementLabel(d.requirement)}</b></div>)}</div></section>}
    <section className="c02-info"><strong>접수 전에 알아두세요</strong><span>방문 영업점과 시간은 지금 고르지 않습니다. 직원 검토 결과가 ‘진행 가능’이면 그때 예약할 수 있도록 안내합니다.</span><span>제출 전에는 접수를 취소할 수 있으며, 제출·심사·직원 결정을 자동으로 수행하지 않습니다.</span></section>
  </main><footer className="c02-footer"><div><span>입력 내용을 확인한 뒤 필수 서류를 확인하세요. 실제 접수는 동의·모의서명·SMS 인증을 마친 후에만 요청됩니다.</span><button type="button" className="c02-secondary" disabled>임시저장</button><button type="button" className="c02-primary" disabled={!taskBffEnabled || busy} onClick={validate}>{busy ? '확인 중' : '필수 서류 확인'}</button></div></footer></div>
}
