import { describe, expect, it } from 'vitest'
import { badgeText, channelLabel, formatReceivedAt, needsCaseLookup, relatedPath, resolvedTaskCode, taskLabel, toneOf } from './notificationView'

describe('notification presentation', () => {
  it('maps spec type codes to the mockup icon tones', () => {
    expect(toneOf('CASE_APPROVED')).toEqual({ tone: 'success', icon: '✓' })
    expect(toneOf('CASE_RECEIVED')).toEqual({ tone: 'info', icon: 'i' })
    expect(toneOf('CASE_CLARIFICATION_REQUEST')).toEqual({ tone: 'question', icon: '?' })
    expect(toneOf('VISIT_CONFIRMED')).toEqual({ tone: 'visit', icon: '▣' })
    expect(toneOf('CASE_REJECTED_DOCUMENT').tone).toBe('warning')
    expect(toneOf('SOMETHING_NEW').tone).toBe('info')
  })

  it('shows email only when the server recorded it as sent', () => {
    expect(channelLabel('SENT')).toBe('웹 알림 · 이메일')
    expect(channelLabel('NOT_REQUIRED')).toBe('웹 알림')
    expect(channelLabel('PENDING')).toBe('웹 알림')
  })

  it('hides a zero badge and caps large counts', () => {
    expect(badgeText(0)).toBeNull()
    expect(badgeText(7)).toBe('7')
    expect(badgeText(100)).toBe('99+')
  })

  it('links only C-02 cases to a customer detail page', () => {
    expect(relatedPath({ taskCode: 'INHERITANCE_DEPOSIT', caseId: 5 })).toBe('/tasks/c02/cases/5')
    expect(relatedPath({ taskCode: 'INHERITANCE_DEPOSIT', caseId: null })).toBeNull()
    expect(relatedPath({ taskCode: 'HOUSING_SAVINGS_CLOSE', caseId: null })).toBeNull()
    expect(taskLabel('INHERITANCE_DEPOSIT')).toBe('C-02 상속예금 사전접수')
    expect(taskLabel(null)).toBeNull()
  })

  it('links a case without a task code only when it is one of the customer C-02 cases', () => {
    const c02CaseIds = new Set([7])
    expect(needsCaseLookup({ taskCode: null, caseId: 7 })).toBe(true)
    expect(needsCaseLookup({ taskCode: 'INHERITANCE_DEPOSIT', caseId: 7 })).toBe(false)
    expect(resolvedTaskCode({ taskCode: null, caseId: 7 }, c02CaseIds)).toBe('INHERITANCE_DEPOSIT')
    expect(relatedPath({ taskCode: null, caseId: 7 }, c02CaseIds)).toBe('/tasks/c02/cases/7')
    expect(relatedPath({ taskCode: null, caseId: 8 }, c02CaseIds)).toBeNull()
    expect(relatedPath({ taskCode: null, caseId: 7 })).toBeNull()
    expect(resolvedTaskCode({ taskCode: 'HOUSING_SAVINGS_CLOSE', caseId: 7 }, c02CaseIds)).toBe('HOUSING_SAVINGS_CLOSE')
  })

  it('formats as MM.DD HH:mm and tolerates bad input', () => {
    expect(formatReceivedAt('not-a-date')).toBe('')
    expect(formatReceivedAt('2026-10-21T02:05:00Z')).toMatch(/^\d{2}\.\d{2} \d{2}:\d{2}$/)
  })
})
