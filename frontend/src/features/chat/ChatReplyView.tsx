import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ChatReply, TaskCode } from '../../api/chat'
import { LoanTable } from './LoanTable'
import './chat-reply.css'

// The six tasks this assistant supports (docs/design-ref/ChatSystem.dc.html). Names and tags are fixed service copy;
// "A · 채팅에서 조회" is only true for the loan inquiry, so the transaction search keeps the plain "A · 조회" tag.
const TASKS = new Map<TaskCode, { name: string; tag: string; tone: 'a' | 'b' | 'c' }>([
  ['TRANSACTION_SEARCH', { name: '거래내역 조회', tag: 'A · 조회', tone: 'a' }],
  ['LOAN_INQUIRY', { name: '대출계좌 정보 조회', tag: 'A · 채팅에서 조회', tone: 'a' }],
  ['REPAYMENT_ORDER_CHANGE', { name: '채무변제 충당순서 변경', tag: 'B · 전용 화면에서 처리', tone: 'b' }],
  ['HOUSING_SAVINGS_CLOSE', { name: '청년 주택드림 청약통장 해지', tag: 'B · 전용 화면에서 처리', tone: 'b' }],
  ['DEBT_ADJUSTMENT', { name: '채무조정 접수', tag: 'C · 접수 후 직원 검토', tone: 'c' }],
  ['INHERITANCE_DEPOSIT', { name: '상속예금 사전접수', tag: 'C · 접수 후 직원 검토', tone: 'c' }],
])

export function BotAvatar() {
  return <span className="chat-avatar" aria-hidden="true">
    <svg width="28" height="28" viewBox="0 0 104 104" fill="none"><line x1="52" y1="14" x2="52" y2="26" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#F2B27F"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#FFFFFF"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#DDECFD"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg>
  </span>
}

type BotMessageProps = { label: string; time: string; children: ReactNode; wide?: boolean; note?: string; after?: ReactNode }

/** wide: full-width bubble without padding for tables (ChatLookup). note: caption under the bubble. after: content below the bubble. */
export function BotMessage({ label, time, children, wide = false, note, after }: BotMessageProps) {
  return <div className="chat-bot">
    <BotAvatar />
    <div className={`chat-bot__body${wide ? ' chat-loan-body' : ''}${after ? ' chat-bot__body--stack' : ''}`}>
      <span className="chat-bot__label">{label}</span>
      <div className={`chat-bubble chat-bubble--bot${wide ? ' chat-loan-bubble' : ''}`}>{children}</div>
      {after}
      {note && <span className="chat-loan-note">{note}</span>}
      <span className="chat-time">{time}</span>
    </div>
  </div>
}

export function replyLabel(reply: ChatReply): string {
  if (reply.kind === 'unsupported') return '지원하지 않는 업무'
  if (reply.kind === 'information') return 'AI 금융 도우미 · 업무 설명'
  if (reply.kind === 'loans') return reply.loans.length > 0 ? 'A-02 대출계좌 정보 조회' : 'A-02 대출계좌 정보 조회 · 없음'
  return 'AI 금융 도우미'
}

export function replyText(reply: ChatReply): string {
  switch (reply.kind) {
    case 'message': return reply.answer
    case 'information': return reply.answer
    case 'clarify': return reply.question
    case 'unsupported': return reply.reason
    case 'composite': return reply.answer ?? '한 번에 한 가지 업무만 도와드릴 수 있어요. 하나씩 말씀해 주세요.'
    case 'notStartable': return reply.answer ?? '이 업무는 아직 채팅에서 바로 시작할 수 없어요.'
    case 'started': return reply.answer
    case 'taskAction': return reply.answer
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

export function ChatReplyView({ reply, time, onTaskAction, actionPending = false }: {
  reply: ChatReply
  time: string
  onTaskAction?: () => void
  actionPending?: boolean
}) {
  if (reply.kind === 'loans' && reply.loans.length > 0) {
    return <BotMessage label={replyLabel(reply)} time={time} wide note="조회만 했어요. 충당순서 변경이나 채무조정은 요청하시면 따로 안내해 드려요.">
      <div className="chat-loan-summary">대출 <strong>{reply.loans.length}건</strong>을 찾았어요.</div>
      <LoanTable loans={reply.loans} />
    </BotMessage>
  }
  if (reply.kind === 'unsupported') {
    return <BotMessage label={replyLabel(reply)} time={time} after={<ul className="chat-reply-pills" aria-label="도와드릴 수 있는 업무">
      {[...TASKS.values()].map(task => <li key={task.name} className="chat-reply-pill">{task.name}</li>)}
    </ul>}>{replyText(reply)}</BotMessage>
  }
  if (reply.kind === 'composite' && reply.tasks) {
    // Only the classifier's split is shown; the server runs none of these tasks (COMPOSITE_NOT_SUPPORTED).
    return <BotMessage label={replyLabel(reply)} time={time} after={<ol className="chat-reply-tasks" aria-label="요청하신 업무">
      {reply.tasks.map((task, index) => {
        const info = TASKS.get(task.taskCode)
        if (!info) return null
        return <li key={`${task.order}-${task.taskCode}`} className="chat-reply-task">
          <span aria-hidden="true" className="chat-reply-task__number">{index + 1}</span>
          <strong className="chat-reply-task__name">{info.name}</strong>
          <span className={`chat-card__badge chat-card__badge--${info.tone}`}>{info.tag}</span>
        </li>
      })}
    </ol>}>{replyText(reply)}</BotMessage>
  }
  if (reply.kind === 'started') {
    return <BotMessage label="AI 금융 도우미" time={time}>
      <p>{reply.answer}</p>
      <Link to={taskPath(reply.taskCode, reply.workItemId)} className="chat-b-button chat-b-button--primary">업무 화면으로 이동</Link>
    </BotMessage>
  }
  if (reply.kind === 'taskAction') {
    return <BotMessage label={replyLabel(reply)} time={time}>
      <p>{reply.answer}</p>
      <button type="button" className="chat-error__retry" disabled={actionPending} onClick={onTaskAction}>
        {actionPending ? '신청 화면을 준비하고 있어요' : reply.action.label}
      </button>
    </BotMessage>
  }
  return <BotMessage label={replyLabel(reply)} time={time}>{replyText(reply)}</BotMessage>
}
