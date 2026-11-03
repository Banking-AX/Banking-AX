import type { ReservationStatus } from '../api/taskApi'

export function reservationStatusLabel(status: ReservationStatus): string {
  switch (status) {
    case 'SELECTED': return '방문 요청 선택'
    case 'PENDING_ASSIGNMENT': return '예약 요청 접수 · 직원 배정 대기'
    case 'CONFIRMED': return '방문 예약 확정'
    case 'NEEDS_MANUAL_ASSIGNMENT': return '직원 수동 배정 대기 · 미확정'
    case 'CANCELED': return '취소'
    case 'EXPIRED': return '만료'
    case 'COMPLETED': return '방문 완료'
  }
}
