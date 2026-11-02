// 개발용 미리보기. 실제 채팅 연결 후 삭제 가능.
import { useSearchParams } from 'react-router-dom'
import { ChatRoomList } from '../features/chat/ChatRoomList'
import { CHAT_ROOMS_NOW, CHAT_ROOMS_SAMPLE } from '../features/chat/chatRoomFixtures'

export function ChatRoomsPreviewPage() {
  const [params] = useSearchParams()
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
          getRoomHref={id => `/preview/chat-rooms?room=${encodeURIComponent(id)}`}
          // 연결 예정.
          onNewChat={() => {}} />
      </div>
      {/* Development note only; the conversation pane comes from ChatModal later. */}
      <div className="chat-rooms-preview__main"><p className="chat-rooms-preview__note">대화 영역은 ChatModal 연결 후 붙습니다</p></div>
    </div>
  </div>
}
