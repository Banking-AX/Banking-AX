import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ChatReply } from '../../api/chat'
import { LoanTable } from './LoanTable'

export function BotAvatar() {
  return <span className="chat-avatar" aria-hidden="true">
    <svg width="28" height="28" viewBox="0 0 104 104" fill="none"><line x1="52" y1="14" x2="52" y2="26" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#F2B27F"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#FFFFFF"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#DDECFD"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg>
  </span>
}

type BotMessageProps = { label: string; time: string; children: ReactNode; wide?: boolean; note?: string }

/** wide: full-width bubble without padding for tables (ChatLookup). note: caption under the bubble. */
export function BotMessage({ label, time, children, wide = false, note }: BotMessageProps) {
  return <div className="chat-bot">
    <BotAvatar />
    <div className={`chat-bot__body${wide ? ' chat-loan-body' : ''}`}>
      <span className="chat-bot__label">{label}</span>
      <div className={`chat-bubble chat-bubble--bot${wide ? ' chat-loan-bubble' : ''}`}>{children}</div>
      {note && <span className="chat-loan-note">{note}</span>}
      <span className="chat-time">{time}</span>
    </div>
  </div>
}

export function replyLabel(reply: ChatReply): string {
  if (reply.kind === 'unsupported') return '지원하지 않는 업무'
  if (reply.kind === 'loans') return reply.loans.length > 0 ? 'A-02 대출계좌 정보 조회' : 'A-02 대출계좌 정보 조회 · 없음'
  return 'AI 금융 도우미'
}

export function replyText(reply: ChatReply): string {
  switch (reply.kind) {
    case 'message': return reply.answer
    case 'clarify': return reply.question
    case 'unsupported': return reply.reason
    case 'composite': return reply.answer ?? '한 번에 한 가지 업무만 도와드릴 수 있어요. 하나씩 말씀해 주세요.'
    case 'notStartable': return reply.answer ?? '이 업무는 아직 채팅에서 바로 시작할 수 없어요.'
    case 'started': return reply.answer
    case 'loans': return reply.answer
  }
}

function taskPath(taskCode: Extract<ChatReply, { kind: 'started' }>['taskCode'], workItemId: number): string {
  switch (taskCode) {
    case 'TRANSACTION_SEARCH': return `/tasks/a01/${workItemId}`
    case 'REPAYMENT_ORDER_CHANGE': return `/tasks/b01/${workItemId}`
    case 'HOUSING_SAVINGS_CLOSE': return `/tasks/b02/${workItemId}`
    case 'INHERITANCE_DEPOSIT': return `/tasks/c02/${workItemId}`
    default: return '/'
  }
}

export function ChatReplyView({ reply, time }: { reply: ChatReply; time: string }) {
  if (reply.kind === 'loans' && reply.loans.length > 0) {
    return <BotMessage label={replyLabel(reply)} time={time} wide note="조회만 했어요.">
      <div className="chat-loan-summary">{reply.answer}</div>
      <LoanTable loans={reply.loans} />
    </BotMessage>
  }
  if (reply.kind === 'started') {
    return <BotMessage label="AI 금융 도우미" time={time}>
      <p>{reply.answer}</p>
      <Link to={taskPath(reply.taskCode, reply.workItemId)} className="chat-b-button chat-b-button--primary">업무 화면으로 이동</Link>
    </BotMessage>
  }
  return <BotMessage label={replyLabel(reply)} time={time}>{replyText(reply)}</BotMessage>
}
