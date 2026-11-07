import type { C02CaseIntake, C02Document, C02OcrDocument, C02OcrSnapshot, C02ReviewItem } from '../../api/taskApi'

export type OcrRowStatus = 'AUTO_MATCHED' | 'MISMATCH' | 'STAFF_REQUIRED' | 'NOT_COMPARED' | 'NOT_EXTRACTED'
export type OcrRow = { label: string; caseValue: string; ocrValue: string; status: OcrRowStatus; reason: string | null }
export type OcrView = { documentFileId: number; documentVersion: number; typeResult: string | null; rows: OcrRow[] }

type Extraction = {
  fields: Record<string, string>
  standard: {
    depositorName?: string
    agent?: { name?: string }
    accounts?: { accountNo?: string }[]
    delegators?: { name?: string; birthDate?: string }[]
    reference?: { depositorBirthDate?: string; agentBirthDate?: string; submittedDate?: string }
  }
}

const OCR_ITEM_PREFIX = 'C02_POA_'
const DOCUMENT_TYPE_ITEM = 'C02_POA_DOCUMENT_TYPE'
const DELEGATOR_ROWS = 5
const NO_VALUE = '—'
/** Every result that leaves work for staff. An Agent item and an OCR item are different checks, so both count. */
const STAFF_CHECK_RESULTS = new Set(['NEEDS_STAFF_REVIEW', 'STAFF_REQUIRED', 'OCR_UNSUPPORTED', 'TYPE_UNVERIFIABLE', 'TYPE_MISMATCH'])

export const isOcrItem = (item: C02ReviewItem) => item.category?.startsWith(OCR_ITEM_PREFIX) ?? false

export const staffCheckCount = (items: C02ReviewItem[]) => items.filter(item => STAFF_CHECK_RESULTS.has(item.result ?? '')).length

/** The newest file of a slot speaks for it; a null snapshot means the lookup failed, not that OCR did not run. */
export function ocrStatusLabel(file: C02Document, snapshot: C02OcrSnapshot | null): string {
  if (!snapshot) return 'OCR 상태 확인 불가 · 원본 확인 필요'
  const result = snapshot.documents.find(item => item.documentFileId === file.documentFileId)
  if (!result) return '자동 인식 미실행'
  if (result.status === 'SUCCEEDED') return 'OCR 내용 추출 완료 · 대조 결과 확인'
  if (result.status === 'NOT_APPLICABLE') return '자동 판독 대상 아님 · 원본 확인 필요'
  return '자동 인식 실패 · 직원 확인 필요'
}

function parseExtraction(extracted: string | null): Extraction | null {
  if (!extracted) return null
  try {
    const value = JSON.parse(extracted) as Partial<Extraction>
    return value.fields && value.standard ? { fields: value.fields, standard: value.standard } : null
  } catch { return null }
}

const dateText = (value: string | null | undefined) => {
  if (!value) return NO_VALUE
  return /^\d{8}$/.test(value) ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6)}` : value
}
const text = (value: string | null | undefined) => value?.trim() || NO_VALUE

/** "Extracted" must be true: a value OCR did not return is reported as absent, never invented. */
const notCompared = (ocrValue: string): OcrRowStatus => ocrValue === NO_VALUE ? 'NOT_EXTRACTED' : 'NOT_COMPARED'

function compared(label: string, caseValue: string, ocrValue: string, item: C02ReviewItem | undefined, staffReason = '자동 대조할 값이 부족합니다.'): OcrRow {
  const result = item?.result
  const status: OcrRowStatus = result === 'AUTO_MATCHED' || result === 'MISMATCH' ? result : result ? 'STAFF_REQUIRED' : notCompared(ocrValue)
  return { label, caseValue, ocrValue, status, reason: result === 'STAFF_REQUIRED' || result === 'OCR_UNSUPPORTED' ? staffReason : null }
}

const shown = (label: string, caseValue: string, ocrValue: string): OcrRow => ({ label, caseValue, ocrValue, status: notCompared(ocrValue), reason: null })

/** Only Spring's stored comparison can make a row "matched"; equal-looking values here stay NOT_COMPARED. */
export function buildOcrView(document: C02OcrDocument, intake: C02CaseIntake | null, items: C02ReviewItem[]): OcrView | null {
  const extraction = parseExtraction(document.extracted)
  if (!extraction) return null
  const { fields, standard } = extraction
  const own = items.filter(item => item.documentFileId === document.documentFileId)
  const item = (field: string) => own.find(row => row.category === `${OCR_ITEM_PREFIX}${field}`)
  const heirs = intake?.heirs ?? []
  const attending = heirs.filter(heir => heir.visitStatus === 'ATTENDING')
  const absent = heirs.filter(heir => heir.visitStatus === 'ABSENT' || heir.visitStatus === 'UNCONFIRMED_ABSENT')
  const agentName = standard.agent?.name
  const delegators = Array.from({ length: DELEGATOR_ROWS }, (_, index) => index + 1)
    .map(row => ({ name: fields[`delegator_${row}_name`]?.trim() ?? '', address: fields[`delegator_${row}_address`] }))
    .filter(row => row.name)
  const delegatorNames = delegators.map(row => row.name).join(', ')
  const heirBirth = (name: string | undefined) => dateText(heirs.find(heir => heir.name === name)?.birthDate)

  const rows: OcrRow[] = [
    compared('예금주', text(intake?.decedentName), text(standard.depositorName), item('depositorName')),
    compared('대리인', text(attending.map(heir => heir.name).join(', ')), text(agentName), item('agent.name')),
    compared('위임인', text(absent.map(heir => heir.name).join(', ')), text(delegatorNames), item('delegators[].name')),
    compared('계좌번호', '정본 정보 없음', text(standard.accounts?.map(account => account.accountNo).join(', ')), item('accounts[].accountNo'), '계좌번호를 대조할 은행 기준정보가 없어 직원 확인이 필요합니다.'),
    compared('예금주 생년월일', dateText(intake?.decedentBirthDate), dateText(standard.reference?.depositorBirthDate), item('depositorBirthDate')),
    compared('대리인 생년월일', heirBirth(agentName), dateText(standard.reference?.agentBirthDate), item('agent.birthDate')),
    ...delegators.map((row, index) => compared(`위임인 생년월일 (${row.name})`, heirBirth(row.name), dateText(standard.delegators?.find(person => person.name === row.name)?.birthDate), item(`delegators[${index}].birthDate`))),
    shown('대리인 주소', '신청정보 없음', text(fields['2_주소'])),
    ...delegators.map(row => shown(`위임인 주소 (${row.name})`, '신청정보 없음', text(row.address))),
    shown('본인과의 관계', '비교 기준 없음', text(fields['2_본인과의 관계'])),
    shown('예금과목', '신청정보 없음', text(fields['1_예금과목'])),
    shown('잔액', '신청정보 없음', text(fields['1_잔액'])),
    shown('작성일', '신청정보 없음', dateText(standard.reference?.submittedDate)),
  ]
  return { documentFileId: document.documentFileId, documentVersion: document.documentVersion, typeResult: own.find(row => row.category === DOCUMENT_TYPE_ITEM)?.result ?? null, rows }
}

export const OCR_ROW_LABELS: Record<OcrRowStatus, string> = {
  AUTO_MATCHED: '일치', MISMATCH: '불일치', STAFF_REQUIRED: '직원 확인', NOT_COMPARED: '추출됨 · 자동 비교 미실시', NOT_EXTRACTED: '추출값 없음',
}
export const OCR_TYPE_LABELS: Record<string, string> = {
  TYPE_MATCHED: '제출된 문서에서 상속 위임장에 필요한 주요 항목을 확인했습니다.',
  TYPE_MISMATCH: '제출된 문서가 위임장이 아닌 것으로 확인됐습니다. 원본을 확인해 주세요.',
  TYPE_UNVERIFIABLE: '위임장 양식을 자동으로 확인하지 못했습니다. 원본을 확인해 주세요.',
}
