import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type C02CustomerCaseSnapshot } from '../api/taskApi'
import { useAuth } from '../auth/AuthContext'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { createC02SignAdapter } from '../features/c02/c02SignAdapter'
import { formatDate, groupDocuments, uploadProgress, visitLabel } from '../features/c02/c02CaseView'
import { SignFlow, type SignDocument } from '../features/sign/SignFlow'

// Same sample path as the sign fixtures (docs/ui-reference ESign demo signature).
const DEMO_PATH = 'M80 140 C 100 60, 150 70, 140 130 S 180 160, 200 90 M 230 70 L 235 150 M 260 110 C 290 70, 330 80, 320 130 S 360 160, 400 100 C 430 70, 470 90, 520 140'

function documentsFor(snapshot: C02CustomerCaseSnapshot, signer: string): SignDocument[] {
  const intake = snapshot.intake
  const heirs = intake?.heirs ?? []
  const applicant = heirs.find(heir => heir.applicant)
  const progress = uploadProgress(groupDocuments(snapshot.requiredDocuments, snapshot.documents, intake))
  const field = (k: string, v: string, emphasis = false) => ({ k, v, emphasis })
  const visitPlans = [...new Set(heirs.map(heir => visitLabel(heir)))]
  return [
    {
      name: '상속예금 해지·지급 사전접수 요청서', ref: 'C02 사전접수 요청서',
      body: '신청인은 피상속인의 예금 해지·지급을 위한 사전검토를 요청하며, 입력한 상속인 정보가 사실과 같음을 확인합니다. 사전검토 결과는 예금 지급 확정이 아닙니다.',
      fields: [
        field('신청인', `${applicant?.name || signer}${applicant?.relationship ? ` (${applicant.relationship})` : ''}`),
        field('피상속인', `${intake?.decedentName ?? '—'} · 사망 ${formatDate(intake?.deathDate)}`),
        field('상속인', `${heirs.map(heir => heir.relationship ? `${heir.name}(${heir.relationship})` : heir.name).join(', ')} · ${heirs.length}명`),
        field('방문 방식', visitPlans.length === 1 && heirs.length > 1 ? `${heirs.length}명 모두 ${visitPlans[0]} (입력값)` : `${visitPlans.join(', ')} (입력값)`),
        field('제출 서류', `${progress.uploaded}건 · 업로드 완료`, true),
      ],
    },
    {
      name: '개인정보 수집·이용 동의서', ref: 'C02 · 필수',
      body: '상속예금 사전검토를 위해 신청인과 상속인의 개인정보를 수집·이용하는 데 동의합니다. (시연 문구)',
      fields: [field('동의자', applicant?.name || signer), field('수집 항목', '성명, 관계, 연락처, 제출 서류'), field('목적', '상속예금 사전검토'), field('보유 기간', '검토 종료 후 모의 기준')],
    },
  ]
}

/** C-02 applicant signature + SMS step. The server re-checks consent, binding and revision on submit. */
export function C02SignPage() {
  const { caseId } = useParams()
  const parsed = Number(caseId)
  const navigate = useNavigate()
  const { user } = useAuth()
  const [snapshot, setSnapshot] = useState<C02CustomerCaseSnapshot | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!taskBffEnabled || !Number.isSafeInteger(parsed) || parsed <= 0) return
    let active = true
    c02Api.snapshot(parsed, `c02-sign-snapshot-${Date.now()}`)
      .then(response => { if (active) setSnapshot(response.data) })
      .catch(cause => { if (active) setError(toUserMessageOr(cause, '접수 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) })
    return () => { active = false }
  }, [parsed])

  const detailHref = `/tasks/c02/cases/${parsed}`
  const adapter = useMemo(() => snapshot ? createC02SignAdapter(snapshot.caseId, snapshot.revision, () => undefined) : null, [snapshot?.caseId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!snapshot || !adapter) {
    return <div className="sign-page"><SiteHeader /><main className="sign-main"><section className="sign-card sign-card--narrow">
      <h1 className="sign-card__title">{error ? '서명 화면을 열 수 없어요.' : '접수 정보를 불러오고 있어요.'}</h1>
      {error && <p role="alert">{error}</p>}
      <Link to={detailHref} className="sign-button sign-button--primary sign-button--link">접수 화면으로</Link>
    </section></main><SiteFooter /></div>
  }
  if (snapshot.caseStatus !== 'DRAFT') return <SignBlocked href={detailHref} text="이미 제출된 접수예요. 진행 상황은 접수 상세에서 확인해 주세요." />
  const progress = uploadProgress(groupDocuments(snapshot.requiredDocuments, snapshot.documents, snapshot.intake))
  if (progress.missingRequired > 0) return <SignBlocked href={`/tasks/c02/${snapshot.workItemId}`} text={`필수 서류 ${progress.missingRequired}건을 먼저 올려 주세요.`} />

  const signer = snapshot.intake?.heirs.find(heir => heir.applicant)?.name || user?.name || '신청인'
  return <SignFlow
    adapter={adapter}
    docs={documentsFor(snapshot, signer)}
    taskName="상속예금 해지 및 지급 사전접수"
    afterText="서명을 마치면 사전검토가 요청돼요"
    note={`지금은 신청인 ${signer}님만 서명해요. 방문 상속인 전원의 서명·도장은 영업점 방문 때 받고, 불참 상속인은 인감 날인 위임장으로 대신해요.`}
    signer={signer}
    backHref={`/tasks/c02/${snapshot.workItemId}`}
    onDone={() => navigate(`${detailHref}?submitted=1`, { replace: true })}
    doneLabel="사전검토 요청 완료 확인"
    storesSignature={false}
    demoPath={DEMO_PATH}
  />
}

function SignBlocked({ href, text }: { href: string; text: string }) {
  return <div className="sign-page"><SiteHeader /><main className="sign-main"><section className="sign-card sign-card--narrow">
    <h1 className="sign-card__title">{text}</h1>
    <Link to={href} className="sign-button sign-button--primary sign-button--link">돌아가기</Link>
  </section></main><SiteFooter /></div>
}
