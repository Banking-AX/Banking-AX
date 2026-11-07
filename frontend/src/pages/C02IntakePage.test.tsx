import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { PowerOfAttorneyPreparationQuestion, shouldShowPoaPreparation } from './C02IntakePage'

describe('C-02 written-delegation preparation choice', () => {
  it('renders all three choices before an heir name or birth date is supplied', () => {
    const html = renderToStaticMarkup(createElement(PowerOfAttorneyPreparationQuestion, { onChange: vi.fn() }))
    expect(html).toContain('위임장을 이미 준비하셨나요?')
    expect(html).toContain('이미 작성한 위임장이 있어요')
    expect(html).toContain('위임장 작성 지원이 필요해요')
    expect(html).toContain('아직 결정하지 않았어요')
  })

  it('shows the question only for absent DOCUMENT delegation', () => {
    expect(shouldShowPoaPreparation('ABSENT', 'DOCUMENT')).toBe(true)
    expect(shouldShowPoaPreparation('ATTENDING', 'NONE')).toBe(false)
    expect(shouldShowPoaPreparation('ABSENT', 'RECEIPT')).toBe(false)
  })
})
