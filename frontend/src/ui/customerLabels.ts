/** Customer-facing labels only; API and DB codes remain unchanged. */
export type C02Requirement = 'REQUIRED' | 'CONDITIONAL' | 'VISIT_ONLY'

const C02_DOCUMENTS: Record<string, { label: string; guidance: string }> = {
  DECEDENT_DETAILED_FAMILY_RELATION: { label: '돌아가신 분의 가족관계증명서(상세)', guidance: '상속 관계를 확인하기 위한 공통 필수 서류입니다.' },
  DECEDENT_BASIC_CERTIFICATE: { label: '돌아가신 분의 기본증명서', guidance: '사망 사실 확인을 위한 공통 필수 서류입니다.' },
  PASSBOOK_AND_SEAL: { label: '통장 및 거래인감(도장)', guidance: '온라인 제출은 필요하지 않습니다. 방문 시 확인할 수 있으며, 상황에 따라 생략될 수 있습니다.' },
  HEIR_IDENTITY_CARD: { label: '상속인 신분증', guidance: '방문하는 상속인별로 필요한 서류입니다.' },
  HEIR_SEAL_OR_SIGNATURE: { label: '상속인 인감 또는 서명', guidance: '온라인 제출은 필요하지 않습니다. 방문 시 지참해 주세요.' },
  ABSENT_HEIR_ID_COPY: { label: '방문하지 않는 상속인의 신분증 사본', guidance: '불참 위임을 선택한 상속인별로 필요한 서류입니다.' },
  ABSENT_HEIR_SEAL_CERTIFICATE: { label: '방문하지 않는 상속인의 인감증명서', guidance: '불참 위임을 선택한 상속인별로 필요합니다. 본인 발급용이며 발급일로부터 3개월 이내여야 합니다.' },
  ABSENT_HEIR_POWER_OF_ATTORNEY: { label: '방문하지 않는 상속인의 위임장', guidance: '불참 위임을 선택한 상속인별로 사전에 작성해 제출해 주세요.' },
  DELEGATION_RECEIPT_NO: { label: '위임장 접수번호', guidance: '슈퍼SOL 위임장을 이용한 경우 접수번호를 확인해 주세요.' },
}
const REQUIREMENTS: Record<C02Requirement, string> = { REQUIRED: '필수 서류', CONDITIONAL: '해당하는 경우 제출', VISIT_ONLY: '방문 시 확인' }
const REVIEW_STATUS: Record<string, string> = { PENDING: '사전점검 대기', RUNNING: '사전점검 중', WAITING_CUSTOMER: '고객 보완 대기', READY_FOR_STAFF: '직원 확인 대기', FAILED_NEEDS_STAFF: '직원 확인 필요', NOT_RUN: '사전점검 전' }
const CASE_STATUS: Record<string, string> = { DRAFT: '작성 중', SUBMITTED: '접수 제출됨', IN_REVIEW: '직원 검토 중', REVIEW_PASSED: '검토 완료', REVIEW_REJECTED_REQUIREMENT: '요건 확인 필요', REVIEW_REJECTED_DOCUMENT: '서류 보완 필요', CANCELED: '접수 취소' }
const DOCUMENT_STATUS: Record<string, string> = { NOT_SUBMITTED: '미제출', UPLOADED: '제출됨', VALIDATION_FAILED: '확인 필요', SYSTEM_VALIDATED: '형식 확인됨', HUMAN_VERIFIED: '직원 확인됨', NEEDS_SUPPLEMENT: '보완 필요' }
const OUT_OF_SCOPE: Record<string, string> = { MINOR_HEIR: '미성년 상속인이 포함되어 있어 온라인 사전접수 대상이 아닙니다. 영업점에서 필요한 절차를 안내받아 주세요.', DEATH_DATE_BEFORE_2008: '사망일이 2008년 1월 1일 이전인 경우에는 이 온라인 사전접수 범위에 포함되지 않습니다. 영업점에 문의해 주세요.' }
const VALIDATION: Record<string, string> = { DECEDENT_REQUIRED: '돌아가신 분의 성명과 사망일을 입력해 주세요.', HEIR_REQUIRED: '상속인 정보를 1명 이상 입력해 주세요.', ONE_APPLICANT_REQUIRED: '신청인은 상속인 중 1명만 선택해 주세요.', HEIR_FIELDS_REQUIRED: '상속인의 성명과 관계를 입력해 주세요.', ABSENT_DELEGATION_REQUIRED: '방문하지 않는 상속인은 위임 방법을 선택해 주세요.', RECEIPT_REQUIRED: '위임장 접수번호를 입력해 주세요.' }
const B02_RATE: Record<string, string> = { NO_INTEREST: '약정에 따른 이율 적용 대상이 아닙니다.', BASE_RATE: '기본 이율이 적용됩니다.', PREFERRED_RATE: '우대 이율 적용 가능 여부를 확인했습니다.', DOCUMENT_REQUIRED: '과세 확인 또는 우대금리 포기 여부를 확인해야 합니다.', MANUAL_REVIEW: '영업점 상담이 필요한 사항입니다.' }

export function c02DocumentLabel(code: string): string { return C02_DOCUMENTS[code]?.label ?? '확인이 필요한 서류' }
export function c02DocumentGuidance(code: string, policyNote?: string): string { return C02_DOCUMENTS[code]?.guidance ?? (policyNote ? `서류 안내: ${policyNote}` : '서류 종류를 확인하지 못했습니다. 영업점에 문의해 주세요.') }
export function c02RequirementLabel(requirement: string): string { return REQUIREMENTS[requirement as C02Requirement] ?? '제출 여부 확인 필요' }
export function c02CaseStatusLabel(status: string | null | undefined): string { return status ? (CASE_STATUS[status] ?? '접수 상태 확인 필요') : '접수 상태 확인 필요' }
export function c02ReviewStatusLabel(status: string | null | undefined): string { return status ? (REVIEW_STATUS[status] ?? '검토 상태 확인 필요') : '사전점검 전' }
export function c02DocumentStatusLabel(status: string | null | undefined): string { return status ? (DOCUMENT_STATUS[status] ?? '서류 상태 확인 필요') : '미제출' }
export function c02OutOfScopeLabel(code: string): string { return OUT_OF_SCOPE[code] ?? '현재 입력하신 조건은 온라인 사전접수 범위를 벗어납니다. 영업점에 문의해 주세요.' }
export function c02ValidationLabel(code: string): string { return VALIDATION[code.split(':', 1)[0]] ?? '입력 내용을 다시 확인해 주세요.' }
export function transactionDirectionLabel(direction: string): string { return direction === 'IN' ? '입금' : direction === 'OUT' ? '출금' : '구분 확인 필요' }
export function accountStatusLabel(status: string): string { return status === 'ACTIVE' ? '사용 가능' : status === 'CLOSED' ? '해지됨' : '상태 확인 필요' }
export function b02AssessmentLabel(rate: string): string { return B02_RATE[rate] ?? '사전 확인 결과를 다시 확인해 주세요.' }
