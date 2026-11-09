// 개발용 미리보기. A-01 채팅 카드를 Mock 샘플로 보여 준다. 서버가 A-01 결과를 주기 시작하면 실제 대화에서 같은 카드가 보인다.
import { useState } from 'react'
import { ChatReplyView } from '../features/chat/ChatReplyView'
import { CHAT_LOOKUP_SAMPLES } from '../features/chat/chatLookupFixtures'

export function ChatLookupPreviewPage() {
  const [sent, setSent] = useState<string | null>(null)
  return <main className="chat-lookup-preview">
    <div className="chat-lookup-preview__frame">
      <h1 className="chat-lookup-preview__title">A-01 채팅 카드 미리보기</h1>
      {/* Pills only record the sentence here; in the dock they send it as a message. */}
      <p aria-live="polite" className="chat-lookup-preview__sent">{sent ? `보낼 문장: ${sent}` : '알약을 누르면 보낼 문장이 여기에 보여요.'}</p>
      {CHAT_LOOKUP_SAMPLES.map(sample => <section key={sample.name} aria-label={sample.name}>
        <p className="chat-lookup-preview__case">Mock 샘플 · {sample.name}</p>
        <ChatReplyView reply={sample.reply} time="오후 3:38" onQuickReply={setSent} />
      </section>)}
    </div>
  </main>
}
