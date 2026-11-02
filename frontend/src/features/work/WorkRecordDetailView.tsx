import { Link } from 'react-router-dom'
import type { B01RecordDetail, B02ManualRecordDetail, EmailNotice, WorkRecordDetail } from '../../api/workRecords'
import { ORDER_LABELS, PENDING_SUBTITLE, PENDING_TITLE } from '../b01/b01Content'

const pad2 = (value: number) => String(value).padStart(2, '0')
const formatTime = (iso: string) => { const d = new Date(iso); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}` }
const formatDayTime = (iso: string) => { const d = new Date(iso); return `${pad2(d.getMonth() + 1)}.${pad2(d.getDate())} ${formatTime(iso)}` }
const formatStamp = (iso: string) => `${new Date(iso).getFullYear()}.${formatDayTime(iso)}`

function emailText(label: string, email: EmailNotice): string {
  return `${label}${email.sentAt ? ` · ${formatTime(email.sentAt)}` : ''}${email.maskedEmail ? ` (${email.maskedEmail})` : ''}`
}

// Fixed design copy for the joint-guarantor warning and the B-02 visit note.
const THIRD_PARTY_TEXT = '연대보증인이 있는 대출이에요. 이 화면은 보증인의 실제 서명·동의를 확인하지 않으며, 직원 확인 주의 항목으로 함께 기록돼요. 기한의 이익이 다시 생기면 별도 신청 없이 비용 → 이자 → 원금으로 자동으로 돌아가고, 직접 재변경은 영업점에서만 할 수 있어요.'
const VISIT_NOTE = 'B 업무 영업점 상담은 방문 예약 대상이 아니에요(방문 예약은 C 업무 진행 가능 결과에만 열려요).'

function B01Body({ detail }: { detail: B01RecordDetail }) {
  const done = detail.status === 'DONE'
  return <div className="work-card__body">
    <h2 className="work-card__heading">변경 전 · 후 충당 순서</h2>
    <div className="work-orders">
      <div className="work-order"><span className="work-order__label">변경 전</span><ol className="work-order__list"><li>{ORDER_LABELS[detail.beforeOrder]}</li></ol><span className="work-order__loan">{detail.loanLabel}</span></div>
      <div className="work-order work-order--after"><span className="work-order__label">변경 후</span><ol className="work-order__list"><li>{ORDER_LABELS[detail.afterOrder]}</li></ol><span className="work-order__loan">{detail.loanLabel}</span></div>
    </div>
    <dl className="work-dl work-dl--divided">
      {detail.targetLoan && <><dt className="work-dl__key">대상 대출</dt><dd className="work-dl__value">{detail.targetLoan}</dd></>}
      {detail.excludedLoan && <><dt className="work-dl__key">제외된 대출</dt><dd className="work-dl__value">{detail.excludedLoan}</dd></>}
      {detail.ruling && <><dt className="work-dl__key">판정 결과</dt><dd className="work-dl__value">{detail.ruling}</dd></>}
      {detail.consent && (detail.consent.at || detail.consent.itemCount !== undefined) && <><dt className="work-dl__key">유의사항 확인</dt><dd className="work-dl__value">
        {[detail.consent.at && formatStamp(detail.consent.at), detail.consent.itemCount !== undefined && `${detail.consent.itemCount}개 항목 확인`].filter(Boolean).join(' · ')}
      </dd></>}
      {done && detail.processedAt && <><dt className="work-dl__key">처리 완료</dt><dd className="work-dl__value work-dl__value--strong">{formatStamp(detail.processedAt)}</dd></>}
      {done && detail.email && <><dt className="work-dl__key">이메일 안내</dt><dd className="work-dl__value">{emailText('결과 안내 메일 발송', detail.email)}</dd></>}
    </dl>
    {detail.thirdPartyProvider && <div className="work-warning"><span aria-hidden="true" className="work-warning__mark">!</span><span>{THIRD_PARTY_TEXT}</span></div>}
    {detail.basis && <span className="work-basis">근거: {detail.basis}</span>}
  </div>
}

function B02ManualBody({ detail }: { detail: B02ManualRecordDetail }) {
  return <div className="work-card__body">
    {detail.explanation && <p className="work-explain">{detail.explanation}</p>}
    <dl className="work-dl">
      {detail.account && <><dt className="work-dl__key">대상 계좌</dt><dd className="work-dl__value">{detail.account}</dd></>}
      {detail.stopReason && <><dt className="work-dl__key">자동처리 중단 사유</dt><dd className="work-dl__value">{detail.stopReason}</dd></>}
      {detail.decidedAt && <><dt className="work-dl__key">판정 시각</dt><dd className="work-dl__value">{formatStamp(detail.decidedAt)}</dd></>}
      {detail.email && <><dt className="work-dl__key">이메일 안내</dt><dd className="work-dl__value">{emailText('상담 필요 안내 메일 발송', detail.email)}</dd></>}
    </dl>
    {detail.documents && detail.documents.length > 0 && <div className="work-docs">
      <strong className="work-docs__title">영업점에 가져가실 서류</strong>
      <ul className="work-docs__list">{detail.documents.map(item => <li key={item}>{item}</li>)}</ul>
      <span className="work-basis">{VISIT_NOTE}</span>
    </div>}
    {detail.basis && <span className="work-basis">근거: {detail.basis}</span>}
  </div>
}

export function WorkRecordDetailView({ detail }: { detail: WorkRecordDetail }) {
  const manual = detail.kind === 'b02manual'
  const pending = detail.kind === 'b01' && detail.status === 'PENDING'
  const tone = manual ? 'manual' : pending ? 'pending' : 'done'
  const badge = manual ? '영업점 상담 필요' : pending ? '처리 대기' : '처리 완료'
  const head = manual ? '자동 해지를 진행하지 않았어요 · 영업점 상담이 필요해요' : pending ? PENDING_TITLE : '충당 순서가 변경되었어요'
  const sub = manual ? '해지 전 단계에서 멈췄으며, 계좌에는 변화가 없어요'
    : pending ? PENDING_SUBTITLE
      : [detail.kind === 'b01' && detail.processedAt && `${formatStamp(detail.processedAt)} 처리`, detail.kind === 'b01' && detail.email && '결과 안내를 이메일로 보냈어요'].filter(Boolean).join(' · ')

  return <main className="work-main">
    <div className="work-intro">
      <nav aria-label="현재 위치" className="work-breadcrumb">
        <Link to="/work" className="work-breadcrumb__link">업무 요청 기록</Link><span aria-hidden="true">›</span><span aria-current="page" className="work-breadcrumb__current">{detail.title}</span>
      </nav>
      <div className="work-title-row">
        <h1 id="work-detail-title" tabIndex={-1} className="work-title">{detail.title}</h1>
        <span className={`work-status work-status--${tone === 'done' ? 'done' : tone === 'pending' ? 'pending' : 'manual'} work-status--large`}>{badge}</span>
      </div>
      <div className="work-chips">
        {detail.code && <span className="work-chip">{detail.code}</span>}
        <span className="work-chip">B 업무 · 정해진 규칙으로 자동 처리</span>
        {detail.chatTitle && <span className="work-chip">요청 대화 · {detail.chatTitle}</span>}
      </div>
    </div>

    <section className={`work-card work-card--${tone}`}>
      <div className="work-card__head">
        <span className="work-card__icon">
          {tone === 'done' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10"></path></svg>}
          {tone === 'manual' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true"><path d="M12 7v6M12 17v.01"></path></svg>}
          {tone === 'pending' && <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>}
        </span>
        <div className="work-card__titles"><strong className="work-card__title">{head}</strong>{sub && <span className="work-card__sub">{sub}</span>}</div>
      </div>
      {detail.kind === 'b01' ? <B01Body detail={detail} /> : <B02ManualBody detail={detail} />}
    </section>

    {detail.timeline.length > 0 && <section aria-labelledby="work-timeline-title" className="work-timeline">
      <h2 id="work-timeline-title" className="work-card__heading">처리 이력</h2>
      <ol className="work-timeline__list">
        {detail.timeline.map((event, index) => <li key={index} className="work-event">
          <span className={`work-event__dot work-event__dot--${event.tone}`}></span>
          <span className="work-event__time">{formatDayTime(event.time)}</span>
          <span className="work-event__text">{event.text}</span>
        </li>)}
      </ol>
    </section>}

    <div className="work-buttons">
      <Link to="/chat" className="work-button work-button--primary">{manual ? '상담 대화로 돌아가기' : '대화로 돌아가 다음 업무 진행'}</Link>
      <Link to="/work" className="work-button work-button--plain">목록으로</Link>
    </div>
    <p className="work-footnote">교육용 시연 화면 · 모든 계좌·금액·약관 내용은 가상이에요. B 업무 결과는 처리 시점에 확정되며, 직원 검토 단계가 없어요.</p>
  </main>
}
