import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { B01ReconnectChoice, ChatReply, TaskCode } from '../../api/chat'
import { LoanTable } from './LoanTable'
import { ParameterRequestBody, TransactionResultBody } from './TransactionResultCard'
import { b01Guide, c02Guide } from './chatGuideCopy'
import './chat-reply.css'
import './chat-task.css'

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
  if (reply.kind === 'transactions') return reply.result.totalElements > 0 ? 'A-01 거래내역 조회 · 결과' : 'A-01 거래내역 조회 · 결과 없음'
  if (reply.kind === 'parameterRequest') return 'A-01 거래내역 조회 · 조건 확인'
  if (reply.kind === 'loans') return reply.loans.length > 0 ? 'A-02 대출계좌 정보 조회' : 'A-02 대출계좌 정보 조회 · 없음'
  if (reply.kind === 'loanDetailsAction') return 'A-02 대출계좌 정보 조회'
  if (reply.kind === 'b01Reconnect') return 'B-01 채무변제 충당순서 변경 · 진행 중인 신청'
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
    case 'loanDetailsAction': return reply.answer
    case 'loans': return reply.answer
    case 'transactions': return reply.answer
    case 'parameterRequest': return reply.answer
    case 'b01Reconnect': return reply.answer
  }
}

const MOVE_ARROW = <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"></path></svg>

function taskPath(taskCode: Extract<ChatReply, { kind: 'started' }>['taskCode'], workItemId: number): string {
  switch (taskCode) {
    case 'TRANSACTION_SEARCH': return `/tasks/a01/${workItemId}`
    case 'REPAYMENT_ORDER_CHANGE': return `/tasks/b01/${workItemId}`
    case 'HOUSING_SAVINGS_CLOSE': return `/tasks/b02/${workItemId}`
    case 'INHERITANCE_DEPOSIT': return `/tasks/c02/${workItemId}`
    default: return '/'
  }
}

/** Identifies one reconnect button within a message, e.g. "CONTINUE_EXISTING:18" or "START_NEW:new". */
export const b01ReconnectKey = (choice: B01ReconnectChoice, workItemId: number | null) => `${choice}:${workItemId ?? 'new'}`

export function ChatReplyView({ reply, time, onTaskAction, actionPending = false, onQuickReply, onB01Reconnect, reconnectPending = null, reconnectLocked = false }: {
  reply: ChatReply
  time: string
  onTaskAction?: () => void
  actionPending?: boolean
  /** Sends a pill's sentence as the customer's message; omitted (pills disabled) while an answer is pending. */
  onQuickReply?: (text: string) => void
  /** Sends the customer's reconnect choice to Spring; omitted when the chat cannot act on it. */
  onB01Reconnect?: (choice: B01ReconnectChoice, workItemId: number | null) => void
  /** b01ReconnectKey of this message's button being processed. */
  reconnectPending?: string | null
  /** Another request is in flight, so every reconnect button is locked. */
  reconnectLocked?: boolean
}) {
  if (reply.kind === 'transactions') {
    return <BotMessage label={replyLabel(reply)} time={time} after={<TransactionResultBody result={reply.result} onQuickReply={onQuickReply} />}>{reply.answer}</BotMessage>
  }
  if (reply.kind === 'parameterRequest') {
    return <BotMessage label={replyLabel(reply)} time={time} after={<ParameterRequestBody request={reply.request} onQuickReply={onQuickReply} />}>{reply.answer}</BotMessage>
  }
  if (reply.kind === 'loans' && reply.loans.length > 0) {
    // ChatLookup.dc.html (a02): card, [대출계좌 상세 보기] to the loan account page (GET /api/v1/customer/loans), then the note.
    return <BotMessage label={replyLabel(reply)} time={time} wide
      // note="금리, 기한의 이익, 충당순서는 상세 화면에서 볼 수 있어요. 조회만 했어요. 충당순서 변경이나 채무조정은 요청하시면 따로 안내해 드려요."
      after={<Link to="/loans" className="chat-lookup-more">대출계좌 상세 보기
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"></path></svg>
      </Link>}>
      <div className="chat-loan-summary">대출 <strong>{reply.loans.length}건</strong>을 찾았어요.</div>
      <LoanTable loans={reply.loans} />
    </BotMessage>
  }
  if (reply.kind === 'loanDetailsAction') {
    return <BotMessage label={replyLabel(reply)} time={time}
      after={<Link to="/loans" className="chat-lookup-more">대출계좌 상세 보기
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"></path></svg>
      </Link>}>
      {reply.answer}
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
  if (reply.kind === 'started' && reply.taskCode === 'INHERITANCE_DEPOSIT') {
    // ChatInherit.dc.html: guidance box and the button to the C-02 intake page (server workItemId).
    const guide = c02Guide()
    return <BotMessage label="AI 금융 도우미" time={time} after={<>
      <div className="chat-task-know">
        <div className="chat-task-know__title">{guide.knowTitle}</div>
        <ul className="chat-task-know__list">
          {guide.know.map(item => <li key={item.text}>{item.lead && <strong>{item.lead}</strong>}{item.lead ? ' ' : ''}{item.text}</li>)}
        </ul>
      </div>
      <div className="chat-b-go">
        <Link to={taskPath(reply.taskCode, reply.workItemId)} className="chat-b-button chat-b-button--primary">상속예금 사전접수 화면으로 이동{MOVE_ARROW}</Link>
      </div>
    </>}>{reply.answer}</BotMessage>
  }
  if (reply.kind === 'notStartable' && reply.taskCode === 'DEBT_ADJUSTMENT') {
    // The server does not start C-01 from the chat; /tasks/c01 explains that the intake is not connected yet.
    return <BotMessage label={replyLabel(reply)} time={time} after={<div className="chat-b-go">
      <Link to="/tasks/c01" className="chat-b-button chat-b-button--primary">채무조정 접수 화면으로 이동{MOVE_ARROW}</Link>
    </div>}>{replyText(reply)}</BotMessage>
  }
  if (reply.kind === 'started') {
    return <BotMessage label="AI 금융 도우미" time={time}>
      <p>{reply.answer}</p>
      <Link to={taskPath(reply.taskCode, reply.workItemId)} className="chat-b-button chat-b-button--primary">업무 화면으로 이동</Link>
    </BotMessage>
  }
  if (reply.kind === 'taskAction') {
    // ChatConsent.dc.html (guide). The button keeps the server action; only its label follows the design decision.
    const guide = b01Guide()
    return <BotMessage label={replyLabel(reply)} time={time} after={<>
      <div className="chat-b-go">
        <button type="button" className="chat-b-button chat-b-button--primary" disabled={actionPending} onClick={onTaskAction}>
          {actionPending ? '신청 화면을 준비하고 있어요' : <>채무변제 충당순서 변경{MOVE_ARROW}</>}
        </button>
        <span className="chat-b-go__note">{guide.moveNote}</span>
      </div>
    </>}>{reply.answer}</BotMessage>
  }
  if (reply.kind === 'b01Reconnect') {
    // Statuses are what the agent saw when it answered; Spring re-checks them when a button is pressed.
    const disabled = !onB01Reconnect || reconnectLocked || reconnectPending !== null
    const startNewKey = b01ReconnectKey('START_NEW', null)
    return <BotMessage label={replyLabel(reply)} time={time} after={<>
      <ul className="chat-reply-tasks" aria-label="진행 중인 충당순서 변경 신청">
        {reply.action.candidates.map((candidate, index) => {
          const key = b01ReconnectKey('CONTINUE_EXISTING', candidate.workItemId)
          return <li key={candidate.workItemId} className="chat-reply-task">
            <span aria-hidden="true" className="chat-reply-task__number">{index + 1}</span>
            <span className="chat-reply-task__name chat-b01-reconnect__name">
              <strong>신청 번호 {candidate.workItemId}</strong>
              <span className="chat-b01-reconnect__meta">{candidate.status === 'ACTIVE' ? '진행 중' : '보류'} · {candidate.originalChatRoomAvailable ? '원래 대화방에서 이어가요' : '신청 화면에서 이어가요'}</span>
            </span>
            <button type="button" className="chat-b-button chat-b-button--outline" disabled={disabled}
              aria-label={`신청 번호 ${candidate.workItemId} 이어하기`}
              onClick={() => onB01Reconnect?.('CONTINUE_EXISTING', candidate.workItemId)}>
              {reconnectPending === key ? '확인하고 있어요' : '이어하기'}
            </button>
          </li>
        })}
      </ul>
      <div className="chat-b-go">
        {reply.action.allowStartNew && <button type="button" className="chat-b-button chat-b-button--primary" disabled={disabled}
          onClick={() => onB01Reconnect?.('START_NEW', null)}>
          {reconnectPending === startNewKey ? '새 신청을 준비하고 있어요' : '새 신청하기'}
        </button>}
        <span className="chat-b-go__note">{reply.action.allowStartNew
          ? '버튼을 누르면 업무 상태를 다시 확인한 뒤 진행해요.'
          : '다른 대화방에 진행 중인 신청이 있어 새 신청은 할 수 없어요. 이어할 신청을 골라 주세요.'}</span>
      </div>
    </>}>{reply.answer}</BotMessage>
  }
  return <BotMessage label={replyLabel(reply)} time={time}>{replyText(reply)}</BotMessage>
}
