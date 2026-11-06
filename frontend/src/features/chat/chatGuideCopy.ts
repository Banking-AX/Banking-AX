import { createElement } from 'react'
import type { DockScreen } from './chatDockContext'

/**
 * Temporary guidance copy (docs/design-ref/ChatConsent.dc.html, ChatInherit.dc.html) shown until the server sends
 * its own text. Only policy and rule explanations live here; sentences that promise a server action
 * (email delivery, cancellation, change deadlines) are left out until the server supports them.
 */

export type GuideItem = { lead?: string; text: string }

const B01_GUIDE = {
  // "끝나면 이 대화로 돌아와요" is left out until the task pages return to the dock.
  moveNote: '지금 탭에서 전용 화면이 열려요. 변경 가능 여부는 그 화면에서 규칙으로 다시 확인해요.',
}

const C02_GUIDE = {
  knowTitle: '미리 알아두세요',
  know: [
    { text: '방문하지 못하는 상속인은 신분증 사본·인감증명서·인감 날인 위임장을 준비하거나, AX뱅크 앱에서 작성한 위임장 접수번호를 입력해요.' },
    { text: '위임장은 영업점에서 작성할 수 없어 미리 준비해야 해요.' },
    { lead: '방문 영업점과 시간은 접수할 때 고르지 않아요.', text: '직원 검토 결과가 ‘진행 가능’이면 그때 예약할 수 있도록 알려드려요.' },
  ] as GuideItem[],
}

/** Server text wins; the temporary copy is used only while the server sends nothing. */
export function serverOr<T>(serverValue: T | null | undefined, fallback: T): T {
  return serverValue ?? fallback
}

export function b01Guide(server?: Partial<typeof B01_GUIDE>) {
  return { moveNote: serverOr(server?.moveNote, B01_GUIDE.moveNote) }
}

export function c02Guide(server?: Partial<typeof C02_GUIDE>) {
  return { knowTitle: serverOr(server?.knowTitle, C02_GUIDE.knowTitle), know: serverOr(server?.know, C02_GUIDE.know) }
}

const LOAN_PAGE_GUIDE = {
  overdueNote: '연체 중인 대출은 충당순서 변경이나 채무조정을 요청할 수 있어요. 요청은 대화에서 하시고, 가능 여부는 각 업무 화면에서 규칙으로 확인해요.',
  dockGuide: '고객님 명의 대출의 잔액·금리·연체 상태예요. 궁금한 점은 물어보세요.',
}

/** Loan account page (docs/design-ref/A02Page.dc.html): rule note under overdue loans and the dock's screen guide. */
export function loanPageGuide(server?: Partial<typeof LOAN_PAGE_GUIDE>) {
  return { overdueNote: serverOr(server?.overdueNote, LOAN_PAGE_GUIDE.overdueNote), dockGuide: serverOr(server?.dockGuide, LOAN_PAGE_GUIDE.dockGuide) }
}

// v58 adds "기간을 바꿔 다시 조회하거나,"; left out until a finished A-01 work item can be searched again.
const A01_PAGE_GUIDE = '대화에서 정한 기간으로 조회한 결과예요. 이어서 물어보세요.'
const MAIN_GUIDE = createElement('span', null, '개인뱅킹 메인 화면이에요. 아래에서 업무를 고르거나 하고 싶은 일을 말씀해 주세요. 신청 기록은 위쪽 ', createElement('strong', null, '[업무 요청 기록]'), '에서 볼 수 있어요.')

/**
 * Dock "현재 화면" / "이 화면 안내" per route, used when the page sets nothing itself (setScreen wins).
 * Pages whose guide changes by step (B-01, B-02, signing, C-02, work records, notifications, visits) only name the
 * screen here; their rows add step-specific guides later. Sentences promising unsupported server actions are left out.
 */
const DOCK_SCREENS: { path: RegExp; screen: DockScreen }[] = [
  { path: /^\/$/, screen: { name: '개인뱅킹 메인', guide: MAIN_GUIDE } },
  { path: /^\/chat(\/.*)?$/, screen: { name: 'AI 대화' } },
  { path: /^\/loans$/, screen: { name: '대출계좌 정보 조회', guide: LOAN_PAGE_GUIDE.dockGuide } },
  { path: /^\/tasks\/a01\/[^/]+$/, screen: { name: '거래내역 조회', guide: A01_PAGE_GUIDE } },
  { path: /^\/tasks\/b01\/[^/]+$/, screen: { name: '충당순서 변경' } },
  { path: /^\/tasks\/b02\/[^/]+$/, screen: { name: '청약통장 해지' } },
  { path: /^\/tasks\/c01$/, screen: { name: '채무조정 접수' } },
  { path: /^\/tasks\/c02\/cases\/[^/]+\/sign$/, screen: { name: '서명·인증' } },
  { path: /^\/tasks\/c02\/cases\/[^/]+$/, screen: { name: '상속예금 사전접수 상세' } },
  { path: /^\/tasks\/c02\/reservations$/, screen: { name: '방문 영업점 예약' } },
  { path: /^\/tasks\/c02(\/[^/]+)?$/, screen: { name: '상속예금 사전접수' } },
  { path: /^\/sign\/[^/]+$/, screen: { name: '서명·인증' } },
  { path: /^\/work$/, screen: { name: '업무 요청 기록' } },
  { path: /^\/work\/[^/]+$/, screen: { name: '업무 요청 기록 상세' } },
  { path: /^\/notifications$/, screen: { name: '알림' } },
]

export function screenForPath(pathname: string): DockScreen | null {
  return DOCK_SCREENS.find(entry => entry.path.test(pathname))?.screen ?? null
}
