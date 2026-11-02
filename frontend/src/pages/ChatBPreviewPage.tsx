// 개발용 미리보기. 실제 채팅 연결 후 삭제 가능.
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ChatBGuide } from '../features/chat/ChatBGuide'
import { ChatBReturn } from '../features/chat/ChatBReturn'
import { ChatTaskStatusBar } from '../features/chat/ChatTaskStatusBar'
import {
  CHAT_B_CITATIONS, CHAT_B_GUIDE_TIME, CHAT_B_LOANS, CHAT_B_NEXT, CHAT_B_PARAGRAPHS, CHAT_B_RESULT_DONE, CHAT_B_RESULT_LABELS,
  CHAT_B_RESULT_PENDING, CHAT_B_RETURN_TIME, chatBTasks,
} from '../features/chat/chatBFixtures'

type Phase = 'guide' | 'return' | 'pending'

export function ChatBPreviewPage() {
  const [params] = useSearchParams()
  const { user } = useAuth()
  const raw = params.get('phase')
  const phase: Phase = raw === 'return' || raw === 'pending' ? raw : 'guide'
  const ownerName = user?.name ?? ''
  const returned = phase !== 'guide'

  return <div className="chat-b-preview">
    <div className="chat-b-preview__panel">
      <h1 className="chat-sr-only">채팅 B-01 안내 미리보기</h1>
      <ChatTaskStatusBar tasks={chatBTasks(returned ? '완료' : '진행 중')} />
      <div className="chat-b-messages">
        <ChatBGuide time={CHAT_B_GUIDE_TIME} ownerName={ownerName} paragraphs={CHAT_B_PARAGRAPHS} citations={CHAT_B_CITATIONS}
          loans={CHAT_B_LOANS} taskHref={returned ? undefined : '/tasks/b01/demo'} />
        {returned && <ChatBReturn returnedAt={CHAT_B_RETURN_TIME} result={phase === 'pending' ? CHAT_B_RESULT_PENDING : CHAT_B_RESULT_DONE}
          {...CHAT_B_RESULT_LABELS} next={CHAT_B_NEXT}
          // 다음 단계에서 연결.
          onAcceptNext={() => {}} />}
      </div>
    </div>
  </div>
}
