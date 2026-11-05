import type { SignDocKey } from '../../api/sign'
import type { SignDocument, SignFlowProps } from './SignFlow'

export type SignSetup = Omit<SignFlowProps, 'adapter'>

// Demo signature paths from docs/design-ref/ESign.dc.html (DEMO_M, DEMO_S).
const DEMO_M = 'M70 150 C 90 70, 120 60, 130 120 S 160 170, 180 100 S 210 50, 225 130 M 250 90 C 270 150, 300 150, 310 95 S 340 60, 360 140 M 380 120 C 420 80, 450 90, 470 125 S 520 150, 540 90'
const DEMO_S = 'M80 140 C 100 60, 150 70, 140 130 S 180 160, 200 90 M 230 70 L 235 150 M 260 110 C 290 70, 330 80, 320 130 S 360 160, 400 100 C 430 70, 470 90, 520 140'

// doneHref: 결과 화면 연결 예정. Every route is '/' until the result pages exist.
const DONE_HREF = '/'

/**
 * Mock sample documents copied from the design (ESign.dc.html, object D). Only the signer name is
 * substituted; loan numbers, amounts and dates are samples until task pages pass real values.
 */
export function getSignSetup(docKey: SignDocKey, signerName: string): SignSetup | null {
  const field = (k: string, v: string, emphasis = false) => ({ k, v, emphasis })
  const b02Application = (reason: string, rate: string, extra: SignDocument['fields'] = []): SignDocument => ({
    name: '청약통장 해지 신청서', ref: 'B02 해지 신청서',
    body: '신청인은 위 청약통장의 해지를 신청하며, 해지 후에는 되돌릴 수 없다는 점을 확인했습니다.',
    fields: [field('신청인', signerName), field('통장', '청년 주택드림 청약통장 270-***-903311'), field('해지 사유', reason), field('적용 이율', rate, true), ...extra],
  })
  const b02Common = {
    taskName: '청년 주택드림 청약통장 해지', afterText: '서명 직후 적용 이율을 다시 판정하고 통장을 해지해요',
    signer: signerName, backHref: '/tasks/b02/demo', doneHref: DONE_HREF, doneLabel: '해지 결과 보기',
    // The backend stores only the B-01 signature PNG.
    storesSignature: false, demoPath: DEMO_M,
  }

  switch (docKey) {
    case 'b01': return {
      taskName: '채무변제 충당순서 변경', afterText: '서명 직후 최신 대출 조건을 다시 확인하고 충당순서를 변경해요',
      note: '화면의 문서는 서명 내용 확인을 위해 재구성한 교육용 모형이며, 고객이 업로드한 PDF 원문 이미지가 아니에요.',
      signer: signerName, backHref: '/tasks/b01/demo', doneHref: DONE_HREF, doneLabel: '변경 결과 보기',
      // B-01 uploads the signature PNG and the backend stores it as the mock signature record.
      storesSignature: true, demoPath: DEMO_M,
      docs: [{
        name: '채무변제충당순서 변경 신청서', ref: 'B01-01 양식',
        body: '신청인은 위 대출의 채무변제 충당순서를 변경해 줄 것을 신청하며, 유의사항(연체이자 계속 발생, 연체정보·법적 절차 동일, 기한의 이익 회복 시 자동 원복, 재변경은 영업점)을 확인했습니다.',
        fields: [field('신청인', signerName), field('대상 대출', 'AX 개인 신용대출 210-***-557731'), field('변경 전', '비용 → 이자 → 원금'), field('변경 후', '비용 → 원금 → 이자', true), field('유의사항 확인', '4개 항목 · 2026.10.20 15:10')],
      }],
    }
    case 'b02': return {
      ...b02Common,
      docs: [b02Application('일반 해지 (청약 당첨 아님)', '청년주택드림 이율 연 4.5% (B2-R4)', [field('과세증명서', '과세사실 없음 · 고객 확인 (OCR 미적용)')])],
    }
    case 'b02base': return {
      ...b02Common,
      docs: [
        b02Application('일반 해지', '기존 청약통장 이율 연 3.1% (B2-R3)'),
        {
          name: '우대이율 포기 동의서', ref: 'B02-02 동의서',
          body: '신청인은 청년주택드림 우대이율을 받지 않고 가입기간에 따른 기존 청약통장 이율을 적용받는 데 동의합니다.',
          fields: [field('동의자', signerName), field('포기하는 이율', '청년주택드림 이율 연 4.5%'), field('적용 이율', '기존 청약통장 이율 연 3.1%', true)],
        },
      ],
    }
    case 'c01': return {
      taskName: '채무조정 접수', afterText: '서명을 마치면 채무조정 접수가 등록돼요',
      signer: signerName, backHref: '/tasks/c01', doneHref: DONE_HREF, doneLabel: '접수 완료 확인',
      storesSignature: false, demoPath: DEMO_M,
      docs: [
        {
          name: '채무조정 요청서', ref: 'C01-04 공식 양식',
          body: '신청인은 개인채무자보호법에 따른 채무조정을 요청하며, 기재 내용이 사실과 같음을 확인합니다. (모의 접수 · 법정 신청 아님)',
          fields: [field('신청인', signerName), field('대상 대출', 'AX 개인 신용대출 210-***-557731 · 원금 18,500,000원'), field('요청사유', '실직, 소득감소'), field('현재 월 소득', '2,800,000원'), field('희망 조정', '분할상환 유예 6개월', true)],
        },
        {
          name: '채무조정안 (고객용)', ref: 'C01-05 · 선택',
          body: '재취업할 때까지 6개월 정도 분할상환을 미뤄 주셨으면 해요.',
          fields: [field('조정 유형', '분할상환 유예', true), field('기간', '6개월'), field('사유', '재취업 시까지')],
        },
        {
          name: '개인(신용)정보 조회·수집·이용·제공 동의서', ref: 'C01-01 · 필수',
          body: '채무조정 검토를 위해 신용정보 조회, 개인정보 수집·이용, 신용회복 관련 기관 제공에 동의합니다. (시연 문구)',
          fields: [field('동의자', signerName), field('조회', '동의'), field('수집·이용', '동의'), field('제3자 제공', '동의'), field('보유 기간', '검토 종료 후 모의 기준')],
        },
      ],
    }
    case 'c02': return {
      taskName: '상속예금 해지 및 지급 사전접수', afterText: '서명을 마치면 사전검토가 요청돼요',
      note: `지금은 신청인 ${signerName}님만 서명해요. 방문 상속인 전원의 서명·도장은 영업점 방문 때 받고, 불참 상속인은 인감 날인 위임장으로 대신해요.`,
      signer: signerName, backHref: '/tasks/c02/demo', doneHref: DONE_HREF, doneLabel: '사전검토 요청 완료 확인',
      storesSignature: false, demoPath: DEMO_S,
      docs: [
        {
          name: '상속예금 해지·지급 사전접수 요청서', ref: 'C02 사전접수 요청서',
          body: '신청인은 피상속인의 예금 해지·지급을 위한 사전검토를 요청하며, 입력한 상속인 정보가 사실과 같음을 확인합니다. 사전검토 결과는 예금 지급 확정이 아닙니다.',
          fields: [field('신청인', `${signerName} (자녀)`), field('피상속인', '이정호 · 사망 2026.09.28'), field('상속인', `정미숙(배우자), ${signerName}, 이도현 · 3명`), field('방문 방식', '3명 모두 방문 예정 (입력값)'), field('제출 서류', '5건 · 업로드 완료', true)],
        },
        {
          name: '개인정보 수집·이용 동의서', ref: 'C02 · 필수',
          body: '상속예금 사전검토를 위해 신청인과 상속인의 개인정보를 수집·이용하는 데 동의합니다. (시연 문구)',
          fields: [field('동의자', signerName), field('수집 항목', '성명, 관계, 연락처, 제출 서류'), field('목적', '상속예금 사전검토'), field('보유 기간', '검토 종료 후 모의 기준')],
        },
      ],
    }
    default: return null
  }
}
