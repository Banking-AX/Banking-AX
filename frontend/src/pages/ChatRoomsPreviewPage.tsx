// 개발용 미리보기. 실제 채팅(도크의 대화 기록)과 같은 ChatRoomList를 Mock 샘플로 보여 준다.
import { useSearchParams } from 'react-router-dom'
import { ChatRoomList } from '../features/chat/ChatRoomList'
import { CHAT_ROOMS_NOW, CHAT_ROOMS_SAMPLE } from '../features/chat/chatRoomFixtures'

export function ChatRoomsPreviewPage() {
  const [params, setParams] = useSearchParams()
  const empty = params.get('empty') === '1'
  const plain = params.get('plain') === '1'
  const rooms = empty ? [] : CHAT_ROOMS_SAMPLE
  const activeRoomId = params.get('room') ?? CHAT_ROOMS_SAMPLE[0].id

  return <div className="chat-rooms-preview">
    <h1 className="chat-sr-only">채팅방 목록 미리보기</h1>
    <div className="chat-rooms-preview__frame">
      <div className="chat-rooms-preview__side">
        <ChatRoomList rooms={rooms} activeRoomId={activeRoomId} now={CHAT_ROOMS_NOW}
          retentionDays={plain ? undefined : 7} draftResetDays={plain ? undefined : 3}
          onSelectRoom={id => { const next = new URLSearchParams(params); next.set('room', id); setParams(next) }}
          // 미리보기에서는 동작하지 않는다. 실제 새 대화는 도크에서 처리한다.
          onNewChat={() => {}} />
      </div>
      {/* Development note only; the real list lives in the dock's [대화 기록] view. */}
      <div className="chat-rooms-preview__main"><p className="chat-rooms-preview__note">실제 목록은 도크의 [대화 기록]에서 열립니다</p></div>
    </div>
  </div>
}
