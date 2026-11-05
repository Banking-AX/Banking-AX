import { renderToStaticMarkup } from 'react-dom/server'
import type { ComponentProps } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { SignAdapter } from '../../api/sign'
import { AuthProvider } from '../../auth/AuthContext'
import { completedSignaturePreview, SignFlow } from './SignFlow'

const adapter: SignAdapter = {
  submitSignature: vi.fn(async () => undefined),
  issueCode: vi.fn(async () => ({ challengeId: 'challenge', maskedPhone: '010-****-0000', expiresAt: new Date(Date.now() + 180_000).toISOString() })),
  verifyCode: vi.fn(async () => ({})),
  complete: vi.fn(async () => undefined),
}

const common = {
  adapter,
  docs: [{ name: '신청서', ref: 'B01', body: '교육용 문서', fields: [{ k: '대상', v: '마스킹 값' }] }],
  taskName: '채무변제 충당순서 변경',
  afterText: '다음 단계로 이동해요',
  signer: '테스트 고객',
  backHref: '/tasks/b01/3',
  doneLabel: '결과 보기',
  storesSignature: true,
  demoPath: 'M10 10 L20 20',
}

function render(props: Partial<ComponentProps<typeof SignFlow>> = {}) {
  return renderToStaticMarkup(<MemoryRouter><AuthProvider><SignFlow {...common} {...props} /></AuthProvider></MemoryRouter>)
}

describe('SignFlow B-01 safety boundaries', () => {
  it('keeps the real signature pad writable and hides the demo signature action', () => {
    const html = render({ initialStep: 'sign', allowDemoSignature: false })
    expect(html).toContain('여기에 서명해 주세요')
    expect(html).not.toContain('예시 서명 넣기')
    expect(html.match(/<svg[^>]*aria-label="서명 입력 칸"[^>]*>/)?.[0]).not.toContain('aria-disabled="true"')
  })

  it('restores a persisted signature at OTP and offers read-only previous-content review', () => {
    const html = render({ initialStep: 'otp', allowDemoSignature: false })
    expect(html).toContain('휴대폰 본인 확인')
    expect(html).toContain('이전 내용 확인')
    expect(html).not.toContain('손글씨 서명</h2>')
  })

  it('never substitutes a demo path when restored signature strokes are unavailable', () => {
    expect(completedSignaturePreview('')).toBeNull()
    expect(completedSignaturePreview('M1 1 L2 2')).toBe('M1 1 L2 2')
  })
})
