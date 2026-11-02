export type ChatTaskState = '완료' | '진행 중' | '대기'

const CIRCLED = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨']
const STATE_CLASS: Record<ChatTaskState, string> = { '완료': 'done', '진행 중': 'active', '대기': 'wait' }

export function ChatTaskStatusBar({ tasks }: { tasks: { label: string; state: ChatTaskState }[] }) {
  return <div role="status" aria-label="요청 업무 진행 상태" className="chat-b-status">
    <span className="chat-b-status__title">요청 업무 {tasks.length}건</span>
    {tasks.map((task, index) => <span key={task.label} className={`chat-b-status__chip chat-b-status__chip--${STATE_CLASS[task.state]}`}>
      {CIRCLED[index] ?? `${index + 1}.`} {task.label} · {task.state}
    </span>)}
  </div>
}
