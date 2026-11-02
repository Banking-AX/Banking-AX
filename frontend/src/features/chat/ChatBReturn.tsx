import { useState } from 'react'
import type { B01Result } from '../../api/b01'
import { PENDING_SUBTITLE, PENDING_TITLE } from '../b01/b01Content'
import { BotAvatar } from './ChatReplyView'

type Props = {
  returnedAt: string
  result: B01Result
  loanLabel: string
  beforeLabel: string
  afterLabel: string
  next?: { label: string; stateLabel: string }
  onAcceptNext?: () => void
}

const pad2 = (value: number) => String(value).padStart(2, '0')
function formatStamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** Result after returning from /tasks/b01 (docs/design-ref/ChatConsent.dc.html, phase return). */
export function ChatBReturn({ returnedAt, result, loanLabel, beforeLabel, afterLabel, next, onAcceptNext }: Props) {
  const [showOther, setShowOther] = useState(false)
  const done = result.status === 'DONE'

  return <div className="chat-b-return">
    <div className="chat-date chat-b-divider">
      <span className="chat-date__line chat-b-divider__line"></span>
      <span className="chat-b-divider__pill">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 14l-5-5 5-5"></path><path d="M4 9h11a5 5 0 0 1 0 10h-3"></path></svg>
        충당순서 변경 화면에서 돌아왔어요 · {returnedAt}
      </span>
      <span className="chat-date__line chat-b-divider__line"></span>
    </div>

    <div className="chat-bot">
      <BotAvatar />
      <div className="chat-bot__body chat-b-body">
        <span className="chat-bot__label">AI 금융 도우미</span>
        <div className={`chat-b-result${done ? '' : ' chat-b-result--pending'}`}>
          <div className="chat-b-result__head">
            {done
              ? <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M8 12l3 3 5-6"></path></svg>
              : <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>}
            <span className="chat-b-result__titles">
              <strong className="chat-b-result__title">{done ? '충당순서 변경이 완료되었어요' : PENDING_TITLE}</strong>
              {!done && <span className="chat-b-result__sub">{PENDING_SUBTITLE}</span>}
            </span>
          </div>
          <dl className="chat-b-dl">
            <dt className="chat-b-dl__key">대상 대출</dt><dd className="chat-b-dl__value">{loanLabel}</dd>
            <dt className="chat-b-dl__key">변경</dt><dd className="chat-b-dl__value">{beforeLabel} ⇒ <strong>{afterLabel}</strong></dd>
            {done && result.processedAt && <><dt className="chat-b-dl__key">처리 일시</dt><dd className="chat-b-dl__value">{formatStamp(result.processedAt)}</dd></>}
            {done && result.maskedEmail && <><dt className="chat-b-dl__key">이메일</dt><dd className="chat-b-dl__value">처리 결과를 {result.maskedEmail}으로 보냈어요</dd></>}
          </dl>
          {/* "처리 결과 자세히 보기" waits for the BDetail screen. */}
        </div>

        {done && next && onAcceptNext && <>
          <div className="chat-bubble chat-bubble--bot chat-b-bubble">
            <span>충당순서 변경 업무가 완료되었습니다. 다음으로 <strong>{next.label}</strong>를 진행하시겠습니까?</span>
          </div>
          {!showOther && <div className="chat-b-actions">
            <button type="button" onClick={onAcceptNext} className="chat-b-button chat-b-button--primary chat-b-button--small">예</button>
            <button type="button" onClick={() => setShowOther(true)} className="chat-b-button chat-b-button--outline">다른 업무 선택</button>
            {/* "나중에 하기" is connected when the request history screen exists. */}
          </div>}
          {showOther && <div className="chat-b-other">
            <strong className="chat-b-other__label">{next.label}</strong>
            <span className="chat-b-other__state">{next.stateLabel}</span>
          </div>}
        </>}
        <span className="chat-time">{returnedAt}</span>
      </div>
    </div>
  </div>
}
