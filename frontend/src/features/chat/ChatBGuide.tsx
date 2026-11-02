import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BotAvatar } from './ChatReplyView'

export type ChatBGuideLoan = { loanId: string; title: string; detail: string; eligible: boolean }

type Props = {
  time: string
  ownerName: string
  paragraphs: ReactNode[]
  citations: string[]
  loans: ChatBGuideLoan[]
  taskHref?: string
}

/** B-01 guidance inside the chat (docs/design-ref/ChatConsent.dc.html, phase guide). */
export function ChatBGuide({ time, ownerName, paragraphs, citations, loans, taskHref }: Props) {
  return <div className="chat-bot">
    <BotAvatar />
    <div className="chat-bot__body chat-b-body">
      <span className="chat-bot__label">AI 금융 도우미</span>
      <div className="chat-bubble chat-bubble--bot chat-b-bubble">
        {paragraphs.map((paragraph, index) => <span key={index}>{paragraph}</span>)}
        {citations.length > 0 && <span className="chat-b-citations">
          {citations.map(citation => <span key={citation} className="chat-b-citation">근거: {citation}</span>)}
        </span>}
      </div>
      <div className="chat-b-loans">
        <div className="chat-b-loans__head">{ownerName}님의 대출</div>
        {loans.map(loan => <div key={loan.loanId} className="chat-b-loan">
          <span className="chat-b-loan__text"><strong className="chat-b-loan__title">{loan.title}</strong><span className="chat-b-loan__detail">{loan.detail}</span></span>
          <span className={`chat-b-badge${loan.eligible ? ' chat-b-badge--ok' : ''}`}>{loan.eligible ? '변경 신청 대상' : '변경 대상 아님'}</span>
        </div>)}
      </div>
      <p className="chat-b-scenario">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="chat-b-scenario__icon"><circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 7.5h.01"></path></svg>
        실제로는 영업점 방문이 필요한 업무예요. 이 프로젝트에서는 ‘규칙이 명확한 영업점 업무를 비대면으로 처리한다면’이라는 가정 시나리오로 보여드려요.
      </p>
      {taskHref && <div className="chat-b-go">
        <Link to={taskHref} className="chat-b-button chat-b-button--primary">충당순서 변경 화면으로 이동
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"></path></svg>
        </Link>
        <span className="chat-b-go__note">지금 탭에서 전용 화면이 열려요. 변경 가능 여부는 그 화면에서 규칙으로 다시 확인하고, 끝나면 이 대화로 돌아와요.</span>
      </div>}
      <span className="chat-time">{time}</span>
    </div>
  </div>
}
