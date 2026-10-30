import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { chatApi, type ChatReply, type ChatSendRequest } from '../../api/chat'
import { BotAvatar, BotMessage, ChatReplyView, replyLabel, replyText } from './ChatReplyView'

type Entry =
  | { id: number; kind: 'user'; text: string; time: string }
  | { id: number; kind: 'bot'; reply: ChatReply; time: string }
  | { id: number; kind: 'error'; request: ChatSendRequest }
type NewEntry = Entry extends infer E ? (E extends Entry ? Omit<E, 'id'> : never) : never

const QUICK_TASKS = [
  { badge: 'A · 조회', tone: 'a', title: '최근 거래내역 조회하기', desc: '채팅에서 바로 조회' },
  { badge: 'A · 조회', tone: 'a', title: '대출계좌 정보 조회하기', desc: '잔액·금리·연체 상태' },
  { badge: 'B · 자동처리', tone: 'b', title: '채무변제 충당순서 변경하기', desc: '조건 확인 후 동의하면 바로 처리' },
  { badge: 'B · 자동처리', tone: 'b', title: '청년 주택드림 청약통장 해지하기', desc: '적용 이율 확인 후 해지' },
  { badge: 'C · 직원 검토', tone: 'c', title: '채무조정 접수하기', desc: '접수 후 직원이 검토해 결과 안내' },
  { badge: 'C · 직원 검토', tone: 'c', title: '상속예금 해지·지급 사전접수', desc: '방문 전 서류 확인 · 검토 후 방문 예약' },
] as const

const MIN_LENGTH = 2
const MAX_LENGTH = 2000
const RECENT_LIMIT = 5
const FOCUSABLE = 'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

const formatTime = (date: Date) => date.toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit' })
const formatDate = (date: Date) => date.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

export function ChatModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [openedAt, setOpenedAt] = useState<Date | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [draft, setDraft] = useState('')
  const [hint, setHint] = useState('')
  const [pending, setPending] = useState(false)
  const [announcement, setAnnouncement] = useState<{ id: number; text: string } | null>(null)

  const dialogRef = useRef<HTMLElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const nextId = useRef(1)
  const controllerRef = useRef<AbortController | null>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  useEffect(() => { if (open && !openedAt) setOpenedAt(new Date()) }, [open, openedAt])

  // Closing keeps the in-flight request; only unmount (e.g. logout) aborts it.
  useEffect(() => () => controllerRef.current?.abort(), [])

  useEffect(() => { if (open && openedAt && !pending) inputRef.current?.focus() }, [open, openedAt, pending])

  useEffect(() => {
    const body = bodyRef.current
    if (body) body.scrollTop = body.scrollHeight
  }, [entries, pending, open, openedAt])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      const dialog = dialogRef.current
      if (!dialog) return
      if (event.key === 'Escape') { event.preventDefault(); onCloseRef.current(); return }
      if (event.key !== 'Tab') return
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) { event.preventDefault(); dialog.focus(); return }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement
      if (!dialog.contains(active) || active === dialog) { event.preventDefault(); (event.shiftKey ? last : first).focus() }
      else if (event.shiftKey && active === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && active === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  const append = (entry: NewEntry) => {
    const id = nextId.current++
    setEntries(list => [...list, { ...entry, id } as Entry])
    return id
  }

  const dispatch = (request: ChatSendRequest) => {
    const controller = new AbortController()
    controllerRef.current = controller
    setPending(true)
    // The input becomes disabled; keep focus inside the dialog meanwhile.
    dialogRef.current?.focus()
    chatApi.send(request, { signal: controller.signal })
      .then(reply => {
        const id = append({ kind: 'bot', reply, time: formatTime(new Date()) })
        setAnnouncement({ id, text: `${replyLabel(reply)}: ${replyText(reply)}` })
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        append({ kind: 'error', request })
      })
      .finally(() => {
        if (controllerRef.current !== controller) return
        controllerRef.current = null
        setPending(false)
      })
  }

  const send = (raw: string) => {
    if (pending) return
    const message = raw.trim()
    if (message.length < MIN_LENGTH) { setHint('두 글자 이상 입력해 주세요.'); return }
    const recentMessages = entries.flatMap(entry => entry.kind === 'user' ? [entry.text] : []).slice(-RECENT_LIMIT)
    setHint('')
    setDraft('')
    append({ kind: 'user', text: message, time: formatTime(new Date()) })
    dispatch({ requestId: crypto.randomUUID(), message, recentMessages })
  }

  const retry = (entry: Extract<Entry, { kind: 'error' }>) => {
    if (pending) return
    setEntries(list => list.filter(item => item.id !== entry.id))
    dispatch(entry.request)
  }

  const onInputKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    // keyCode 229: IME composition in browsers that do not set isComposing on keydown.
    if (event.key !== 'Enter' || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
    event.preventDefault()
    send(draft)
  }

  if (!open || !openedAt) return null

  return <div className="chat-layer">
    <div className="chat-backdrop" aria-hidden="true"></div>
    <section ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="chat-title" tabIndex={-1} className="chat-dialog">
      <header className="chat-header">
        <span className="chat-header__logo" aria-hidden="true">
          <svg width="30" height="30" viewBox="0 0 104 104" fill="none"><line x1="52" y1="14" x2="52" y2="26" stroke="#2E70C3" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#C9682A"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#2E70C3"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#EEF6FE"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg>
        </span>
        <div className="chat-header__titles">
          <h2 id="chat-title" className="chat-header__title">AI 금융 도우미</h2>
          <span className="chat-header__subtitle">AX뱅크 개인뱅킹 · 교육용 시연</span>
        </div>
        <button type="button" aria-label="닫기" onClick={onClose} className="chat-header__close">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"></path></svg>
        </button>
      </header>

      <div ref={bodyRef} className="chat-body">
        <div className="chat-date"><span className="chat-date__line"></span>{formatDate(openedAt)}<span className="chat-date__line"></span></div>

        <BotMessage label="AI 금융 도우미" time={formatTime(openedAt)}>안녕하세요! AX AI 금융 도우미입니다.<br />어떤 금융업무를 도와드릴까요?</BotMessage>

        <div className="chat-quick">
          <span className="chat-quick__title">자주 찾는 업무</span>
          <div className="chat-quick__grid">
            {QUICK_TASKS.map(task => <button key={task.title} type="button" disabled={pending} onClick={() => send(task.title)} className="chat-card">
              <span className={`chat-card__badge chat-card__badge--${task.tone}`}>{task.badge}</span>
              <span className="chat-card__title">{task.title}</span>
              <span className="chat-card__desc">{task.desc}</span>
            </button>)}
          </div>
        </div>

        {entries.map(entry => {
          if (entry.kind === 'user') return <div key={entry.id} className="chat-user">
            <div className="chat-bubble chat-bubble--user">{entry.text}</div>
            <span className="chat-time">{entry.time}</span>
          </div>
          if (entry.kind === 'bot') return <ChatReplyView key={entry.id} reply={entry.reply} time={entry.time} />
          return <div key={entry.id} className="chat-error">
            <span className="chat-spacer" aria-hidden="true"></span>
            <div role="alert" className="chat-error__box">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#B42318" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v6M12 16.5h.01"></path></svg>
              <span className="chat-error__text">답변을 만드는 중에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.</span>
              <button type="button" disabled={pending} onClick={() => retry(entry)} className="chat-error__retry">다시 시도</button>
            </div>
          </div>
        })}

        {pending && <div className="chat-typing">
          <BotAvatar />
          <div role="status" className="chat-typing__bubble">
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__text">요청 내용을 확인하고 있어요 · 답변 중에는 입력이 잠겨요</span>
          </div>
        </div>}

        {/* A new keyed node is announced even when the text repeats. */}
        <div aria-live="polite" className="chat-sr-only">{announcement && <span key={announcement.id}>{announcement.text}</span>}</div>
      </div>

      <footer className="chat-footer">
        <div className="chat-composer">
          <label htmlFor="chat-input" className="chat-sr-only">메시지 입력</label>
          <input id="chat-input" ref={inputRef} type="text" value={draft} maxLength={MAX_LENGTH} disabled={pending}
            onChange={e => { setDraft(e.target.value); if (hint) setHint('') }} onKeyDown={onInputKeyDown}
            aria-describedby="chat-input-hint"
            placeholder={pending ? 'AI가 답변을 준비하는 동안에는 입력할 수 없어요' : '예) 거래내역도 조회하고 채무조정도 접수하고 싶어요'}
            className="chat-input" />
          <button type="button" aria-label="메시지 보내기" onClick={() => send(draft)} disabled={pending} className="chat-send">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 12l16-8-6 16-3-7z"></path><path d="M11 13l9-9"></path></svg>
          </button>
        </div>
        <p id="chat-input-hint" aria-live="polite" className="chat-hint">{hint}</p>
        <p className="chat-footnote">업무 설명은 은행 안내 문서를 근거로 드려요. 자격 판정은 규칙과 직원이 해요.</p>
      </footer>
    </section>
  </div>
}
