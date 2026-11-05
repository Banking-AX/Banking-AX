import { describe, expect, it } from 'vitest'
import { ApiError } from './client'
import { toUserMessage, toUserMessageOr } from './errorMessages'

describe('customer error messages', () => {
  it('does not expose a Spring exception message', () => {
    const error = new ApiError('DOCUMENT_SLOT_UNKNOWN', 400, 'The document slot is not required for this C-02 case.')
    expect(toUserMessage(error)).toBe('제출할 서류 항목을 확인할 수 없어요.')
  })

  it('keeps a Korean caller fallback for non-contract failures', () => {
    expect(toUserMessageOr(new Error('Connection refused'), '접수 정보를 불러오지 못했습니다.')).toBe('접수 정보를 불러오지 못했습니다.')
  })

  it('explains that an unavailable original is not a successfully opened document', () => {
    const error = new ApiError('DOCUMENT_NOT_FOUND', 404, 'Stored document file was not found.')
    expect(toUserMessage(error)).toBe('원본 파일을 확인할 수 없습니다. 고객에게 다시 제출을 요청하거나 문서 기록을 확인해 주세요.')
  })
})
