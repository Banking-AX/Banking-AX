import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { B01ReconnectAction, ChatReply } from '../../api/chat'
import { b01ReconnectKey, ChatReplyView } from './ChatReplyView'

const action = (allowStartNew: boolean): B01ReconnectAction => ({
  type: 'SELECT_B01_RECONNECT', allowStartNew,
  candidates: [
    { workItemId: 18, status: 'ACTIVE', originalChatRoomId: 5, originalChatRoomAvailable: true },
    { workItemId: 3, status: 'ON_HOLD', originalChatRoomId: null, originalChatRoomAvailable: false },
  ],
})

function render(reply: ChatReply, props: { pending?: string | null; locked?: boolean; handler?: boolean } = {}) {
  const { pending = null, locked = false, handler = true } = props
  return renderToStaticMarkup(<MemoryRouter><ChatReplyView reply={reply} time="오전 10:00"
    onB01Reconnect={handler ? () => undefined : undefined} reconnectPending={pending} reconnectLocked={locked} /></MemoryRouter>)
}

const buttons = (html: string) => [...html.matchAll(/<button[^>]*>.*?<\/button>/g)].map(match => match[0])

describe('B-01 reconnect buttons in the chat bubble', () => {
  it('lists every candidate with its own continue button and no financial fields', () => {
    const html = render({ kind: 'b01Reconnect', answer: '진행 중인 업무가 있습니다.', action: action(false) })
    expect(html).toContain('신청 번호 18')
    expect(html).toContain('신청 번호 3')
    expect(html).toContain('진행 중 · 원래 대화방에서 이어가요')
    expect(html).toContain('보류 · 신청 화면에서 이어가요')
    expect(html).toContain('aria-label="신청 번호 18 이어하기"')
    expect(html).toContain('aria-label="신청 번호 3 이어하기"')
    expect(html).not.toMatch(/잔액|금리|원$/)
  })

  it('hides 새 신청하기 unless the action allows a new application', () => {
    expect(render({ kind: 'b01Reconnect', answer: '안내', action: action(false) })).not.toContain('새 신청하기')
    expect(render({ kind: 'b01Reconnect', answer: '안내', action: action(true) })).toContain('새 신청하기')
  })

  it('locks every button while one choice is being processed', () => {
    const html = render({ kind: 'b01Reconnect', answer: '안내', action: action(true) },
      { pending: b01ReconnectKey('CONTINUE_EXISTING', 18) })
    expect(html).toContain('확인하고 있어요')
    expect(buttons(html)).toHaveLength(3)
    expect(buttons(html).every(button => button.includes('disabled'))).toBe(true)
  })

  it('locks the buttons while another request runs or when the chat cannot act', () => {
    const reply: ChatReply = { kind: 'b01Reconnect', answer: '안내', action: action(true) }
    expect(buttons(render(reply, { locked: true })).every(button => button.includes('disabled'))).toBe(true)
    expect(buttons(render(reply, { handler: false })).every(button => button.includes('disabled'))).toBe(true)
    expect(buttons(render(reply)).some(button => button.includes('disabled'))).toBe(false)
  })

  it('keeps the existing B-01 taskAction button', () => {
    const html = renderToStaticMarkup(<MemoryRouter><ChatReplyView time="오전 10:00" onTaskAction={() => undefined}
      reply={{ kind: 'taskAction', answer: '신청 화면에서 진행해 주세요.', action: { taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'a' } }} /></MemoryRouter>)
    expect(html).toContain('채무변제 충당순서 변경')
    expect(html).not.toContain('이어하기')
  })
})
