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

  it('renders the B-01 loan candidates as readable numbered rows', () => {
    const html = render({ kind: 'b01LoanSelection',
      answer: '말씀하신 업무는 채무변제 충당순서 변경입니다.\n고객님이 보유한 대출 중 변경할 대출을 선택해 주세요.\n1. B01-T01 가상 가계대출(MOCK-***-T01)\n변경할 대출 번호를 입력해 주세요.',
      action: { type: 'SELECT_B01_LOAN', candidates: [
        { number: 1, loanId: 4, loanName: 'B01-T01 가상 가계대출', loanNoMasked: 'MOCK-***-T01' },
        { number: 2, loanId: 5, loanName: '테스트 대출', loanNoMasked: '210-***-000005' },
      ] } })
    expect(html).toContain('보유 대출 2건')
    expect(html).toContain('B01-T01 가상 가계대출')
    expect(html).toContain('MOCK-***-T01')
    expect(html).toContain('변경할 대출 번호를 입력해 주세요.')
    expect(html).toContain('고객님이 보유한 대출 중 변경할 대출을 선택해 주세요.')
    expect(html).not.toContain('가계대출(MOCK-***-T01)')
  })

  it('shows separate application form download and task-page buttons after ELIGIBLE', () => {
    const html = renderToStaticMarkup(<MemoryRouter><ChatReplyView time="오전 10:00"
      onTaskAction={() => undefined} onDocumentDownload={() => undefined}
      reply={{ kind: 'taskAction', answer: '신청서를 다운로드하여 작성하고 서명해 주세요.',
        action: { taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'a' },
        documentAction: { type: 'DOWNLOAD_B01_APPLICATION',
          fileName: 'b01-repayment-order-change-application-201903.pdf',
          downloadPath: '/api/v1/customer/b01/application-form' } }} /></MemoryRouter>)
    expect(html).toContain('채무변제 충당순서 변경 신청서')
    expect(html).toContain('양식 코드 B01-01')
    expect(html).toContain('신청서 다운로드</button>')
    expect(html).toContain('작성과 서명을 완료했다면 신청 페이지로 이동해 해당 서류를 업로드해 주세요.')
    expect(html).toContain('신청 페이지로 이동')
    expect(html.match(/<button/g)).toHaveLength(2)
  })

  it('keeps the existing move button when a task action has no form download', () => {
    const html = renderToStaticMarkup(<MemoryRouter><ChatReplyView time="오전 10:00"
      onTaskAction={() => undefined}
      reply={{ kind: 'taskAction', answer: '안내',
        action: { taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'b' } }} /></MemoryRouter>)
    expect(html).not.toContain('신청서 다운로드')
    expect(html).toContain('채무변제 충당순서 변경')
  })
})
