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
