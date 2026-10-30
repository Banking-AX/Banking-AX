import type { ReactNode } from 'react'
import type { ChatReply } from '../../api/chat'

export function BotAvatar() {
  return <span className="chat-avatar" aria-hidden="true">
    <svg width="28" height="28" viewBox="0 0 104 104" fill="none"><line x1="52" y1="14" x2="52" y2="26" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#F2B27F"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#FFFFFF"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#DDECFD"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg>
  </span>
}

export function BotMessage({ label, time, children }: { label: string; time: string; children: ReactNode }) {
  return <div className="chat-bot">
    <BotAvatar />
    <div className="chat-bot__body">
      <span className="chat-bot__label">{label}</span>
      <div className="chat-bubble chat-bubble--bot">{children}</div>
      <span className="chat-time">{time}</span>
    </div>
  </div>
}

export function replyLabel(reply: ChatReply): string {
  return reply.kind === 'unsupported' ? '지원하지 않는 업무' : 'AI 금융 도우미'
}

export function replyText(reply: ChatReply): string {
  switch (reply.kind) {
    case 'clarify': return reply.question
    case 'unsupported': return reply.reason
    case 'composite': return '한 번에 한 가지 업무만 도와드릴 수 있어요. 하나씩 말씀해 주세요.'
    case 'notStartable': return '이 업무는 아직 채팅에서 바로 시작할 수 없어요.'
  }
}

export function ChatReplyView({ reply, time }: { reply: ChatReply; time: string }) {
  return <BotMessage label={replyLabel(reply)} time={time}>{replyText(reply)}</BotMessage>
}
