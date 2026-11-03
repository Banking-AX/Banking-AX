import { describe, expect, it } from 'vitest'
import { reservationStatusLabel } from './reservationStatus'

describe('reservation status copy', () => {
  it('does not present a pending assignment request as a confirmed visit', () => {
    expect(reservationStatusLabel('PENDING_ASSIGNMENT')).toContain('예약 요청 접수')
    expect(reservationStatusLabel('PENDING_ASSIGNMENT')).toContain('직원 배정 대기')
    expect(reservationStatusLabel('PENDING_ASSIGNMENT')).not.toContain('확정')
  })

  it('uses confirmed wording only for the confirmed status', () => {
    expect(reservationStatusLabel('CONFIRMED')).toBe('방문 예약 확정')
    expect(reservationStatusLabel('NEEDS_MANUAL_ASSIGNMENT')).toContain('미확정')
  })
})
