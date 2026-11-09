import type { C02DocumentReviewRow, C02ReviewItem, C02StaffCase } from '../../api/taskApi'
import { c02DocumentLabel } from '../../ui/customerLabels'
import { isOcrItem, staffCheckCount } from './c02OcrView'

/** Display text only: API codes, stored values and Agent output stay as they are. */
const STATUS_LABELS: Record<string, string> = {
  DRAFT: '작성 중', SUBMITTED: '접수 완료 · 검토 대기', IN_REVIEW: '직원 검토 중', CANCELED: '고객 취소',
  REVIEW_PASSED: '검토 완료 · 진행 가능', REVIEW_REJECTED_REQUIREMENT: '검토 완료 · 요건 미충족', REVIEW_REJECTED_DOCUMENT: '검토 완료 · 서류 보완 필요',
  PENDING: '사전점검 대기', RUNNING: '사전점검 중', READY_FOR_STAFF: '사전점검 완료 · 직원 확인 대기', WAITING_CUSTOMER: '고객 확인 대기', FAILED_NEEDS_STAFF: '사전점검 실패 · 직원 확인 필요',
  PROCEED: '진행 가능', REQUIREMENT_FAILED: '요건 미충족', DOCUMENT_INSUFFICIENT: '서류 보완 필요',
  UPLOADED: '제출됨', NOT_SUBMITTED: '미제출', VALIDATION_FAILED: '파일 확인 실패', NEEDS_SUPPLEMENT: '보완 필요', SYSTEM_VALIDATED: '파일 확인됨', HUMAN_VERIFIED: '직원 확인 완료',
}
const RUN_LABELS: Record<string, string> = { SUCCEEDED: '실행 완료', FAILED: '실행 실패', NOT_RUN: '아직 실행하지 않음' }
const SOURCE_LABELS: Record<string, string> = { SPRING_RULE: '시스템 규칙', AGENT_SEMANTIC: '직원 Agent 안내', PRE_SUBMISSION: '서류 정책 안내' }

const AGENT_ITEMS: Record<string, { title: string; body?: string }> = {
  DELEGATION_DOCUMENT_PRIORITY: { title: '위임서류 우선 확인', body: '방문하지 않는 상속인이 서면 위임을 선택했습니다. 위임장, 신분증 사본 및 인감증명서를 먼저 확인해 주세요.' },
  DOCUMENT_POLICY: { title: '일반 증빙서류 확인', body: '가족관계증명서와 기본증명서 등 일반 증빙서류는 자동 내용 판독 대상이 아닙니다. 제출된 원본을 확인해 주세요.' },
  POLICY_EVIDENCE: { title: '업무기준 참고' },
  VISIT_ATTENDANCE: { title: '방문 여부 확인' },
  DOCUMENT_MISSING: { title: '필수서류 미제출', body: '제출되지 않은 필수서류가 있습니다. 서류 목록을 확인해 주세요.' },
  DOCUMENT_SLOT: { title: '필수서류 미제출', body: '제출되지 않은 필수서류가 있습니다. 서류 목록을 확인해 주세요.' },
  DOCUMENT_NEEDS_SUPPLEMENT: { title: '서류 보완 필요', body: '보완이 필요한 제출 서류가 있습니다. 서류 목록을 확인해 주세요.' },
  DOCUMENT_METADATA_MISSING: { title: '제출 파일 확인 필요', body: '제출 상태와 저장된 파일 정보가 맞지 않는 서류가 있습니다. 서류 목록을 확인해 주세요.' },
  REQUIRED_INPUT: { title: '신청정보 확인' },
  SCOPE: { title: '사전접수 범위 확인' },
  INTAKE_RULE: { title: '접수 규칙 확인' },
  SYSTEM_HANDOFF: { title: '자동 점검 확인 필요' },
}
const FIELD_NAMES: [RegExp, string, '성명' | '생년월일' | '계좌번호'][] = [
  [/^C02_POA_depositorName$/, '예금주', '성명'], [/^C02_POA_agent\.name$/, '대리인', '성명'], [/^C02_POA_delegators\[\]\.name$/, '위임인', '성명'],
  [/^C02_POA_depositorBirthDate$/, '예금주', '생년월일'], [/^C02_POA_agent\.birthDate$/, '대리인', '생년월일'], [/^C02_POA_delegators\[\d+\]\.birthDate$/, '위임인', '생년월일'],
  [/^C02_POA_accounts\[\]\.accountNo$/, '', '계좌번호'],
]
const POWER_OF_ATTORNEY = 'ABSENT_HEIR_POWER_OF_ATTORNEY'

export const statusLabel = (value: string | null | undefined) => value ? STATUS_LABELS[value] ?? value : '미확인'
export const runLabel = (value: string | null | undefined) => value ? RUN_LABELS[value] ?? value : '미확인'

/** Spring results and Agent guidance keep separate source labels; none of these texts came from an LLM. */
export function agentItemView(item: C02ReviewItem) {
  const known = AGENT_ITEMS[item.category ?? '']
  const message = item.message?.replace(/^업무 근거:\s*/, '')
  return {
    title: known?.title ?? '기타 검토 항목',
    body: known?.body ?? message ?? '상세 내용이 제공되지 않았습니다.',
    source: item.category === 'POLICY_EVIDENCE' ? '업무 문서 참고' : SOURCE_LABELS[item.source ?? ''] ?? '출처 미확인',
  }
}

const fieldOf = (item: C02ReviewItem) => FIELD_NAMES.find(([pattern]) => pattern.test(item.category ?? ''))
const unique = (values: string[]) => [...new Set(values)]

/** "예금주·대리인 성명, 위임인 생년월일" from the fields actually present in the results. */
function fieldList(items: C02ReviewItem[]) {
  const fields = items.map(fieldOf).filter(field => field !== undefined)
  const names = (kind: string) => unique(fields.filter(field => field[2] === kind).map(field => field[1])).join('·')
  return [names('성명') && `${names('성명')} 성명`, names('생년월일') && `${names('생년월일')} 생년월일`, fields.some(field => field[2] === '계좌번호') ? '계좌번호' : '']
    .filter(Boolean).join(', ')
}

export type SummaryLine = { title: string; body: string; warning?: boolean }

/** Every name and count comes from the response, so a Case with no match never shows a success sentence. */
export function staffSummaryLines(data: C02StaffCase): SummaryLine[] {
  const items = data.precheck?.reviewItems ?? []
  const summary = data.summary
  const absent = data.intake?.heirs.filter(heir => heir.visitStatus === 'ABSENT' || heir.visitStatus === 'UNCONFIRMED_ABSENT') ?? []
  const written = absent.filter(heir => heir.delegationMethod === 'DOCUMENT').map(heir => heir.name)
  const receipt = absent.filter(heir => heir.delegationMethod === 'RECEIPT').map(heir => heir.name)
  const ocr = items.filter(isOcrItem)
  const matched = ocr.filter(item => item.result === 'AUTO_MATCHED')
  const mismatched = ocr.filter(item => item.result === 'MISMATCH')
  const staffFields = ocr.filter(item => item.result === 'STAFF_REQUIRED' || item.result === 'OCR_UNSUPPORTED')
  const expectsPoa = data.requiredDocuments.some(slot => slot.documentTypeCode === POWER_OF_ATTORNEY)
  const typeResult = ocr.find(item => item.category === 'C02_POA_DOCUMENT_TYPE')?.result

  const lines: SummaryLine[] = [{
    title: '서류 제출 현황',
    body: summary?.missingRequiredCount === 0
      ? `필수서류 ${summary.requiredUploadCount}건이 모두 업로드되었습니다. 서류 내용과 원본의 적정성은 직원 확인이 필요합니다.`
      : `필수서류 ${summary?.requiredUploadCount ?? 0}건 중 ${summary?.missingRequiredCount ?? 0}건이 제출되지 않았습니다.`,
    warning: summary?.missingRequiredCount !== 0,
  }]
  if (data.precheck?.agentRunStatus === 'NOT_RUN') lines.push({ title: '자동 사전점검', body: '사전점검이 아직 실행되지 않았습니다.', warning: true })
  if (matched.length > 0) lines.push({ title: '위임장 자동 대조', body: `위임장의 ${fieldList(matched)}이 신청정보와 일치합니다. (${matched.length}건)` })
  else if (typeResult && typeResult !== 'TYPE_MATCHED') lines.push({ title: '위임장 자동 대조', body: '위임장 양식을 자동으로 확인하지 못해 자동 대조를 하지 않았습니다. 원본을 직접 확인해 주세요.', warning: true })
  else if (expectsPoa) lines.push({ title: '위임장 자동 대조', body: '자동 대조 결과가 없습니다. 위임장 원본을 직접 확인해 주세요.', warning: true })
  if (mismatched.length > 0) lines.push({ title: '불일치 확인', body: `위임장의 ${fieldList(mismatched)}이 신청정보와 다릅니다. 원본과 신청정보를 대조해 주세요. (${mismatched.length}건)`, warning: true })

  const extra = [fieldList(staffFields), written.length > 0 ? '위임인의 서명·인감 및 위임 의사' : '서류 원본과 상속관계'].filter(Boolean)
  lines.push({ title: '추가 확인 필요', body: `${extra.join(', ')}는 직원이 직접 확인해야 합니다.` })
  if (written.length > 0) lines.push({ title: '우선 확인할 서류', body: `미방문 상속인 ${written.join(', ')}의 위임장, 신분증 사본 및 인감증명서를 우선 확인해 주세요.` })
  if (receipt.length > 0) lines.push({ title: '우선 확인할 서류', body: `미방문 상속인 ${receipt.join(', ')}의 위임 접수번호를 확인해 주세요.` })

  const total = staffCheckCount(items)
  const fromOcr = staffCheckCount(ocr)
  lines.push({ title: '건수 기준', body: `직원 확인 ${total}건은 자동 대조에서 넘어온 ${fromOcr}건과 사전점검 안내 ${total - fromOcr}건입니다. 자동 대조 일치 ${matched.length}건은 여기에 포함되지 않습니다.` })
  return lines
}

/** Left in a draft where only staff know the content; the page refuses to register a message that still has one. */
export const STAFF_INPUT_MARK = '[직원 입력'

/** One line per missing or supplement-marked file, from the saved checks: nothing is inferred or written by the model. */
export function noticeDocumentLines(rows: C02DocumentReviewRow[]): string[] {
  return rows.flatMap(row => {
    const name = c02DocumentLabel(row.documentTypeCode) + (row.personName ? ` (${row.personName})` : '')
    if (row.documentFileId === null) return row.requirement === 'REQUIRED' ? [`- ${name}: 아직 제출되지 않았어요`] : []
    return row.review?.result === 'SUPPLEMENT_REQUIRED' ? [`- ${name}: ${row.review.reason}`] : []
  })
}

/**
 * The customer notice for the result staff selected. Null when there is nothing case-specific to say yet, so the
 * opinion's own draft is shown instead. Staff still edit and register it; nothing here sends or decides.
 */
export function customerNoticeDraft(outcome: string, lines: string[]): string | null {
  const list = lines.join('\n')
  if (outcome === 'PROCEED') return '사전검토 결과 방문 진행이 가능해요. 방문하실 영업점과 시간을 예약해 주세요. 제출하신 서류의 원본과 상속관계는 방문 때 직원이 다시 확인해요.' + (list ? `\n\n방문 전에 아래 서류를 다시 준비해 주세요.\n${list}` : '')
  if (outcome === 'DOCUMENT_INSUFFICIENT') return `제출하신 서류로는 확인을 마칠 수 없어 이번 사전접수는 여기서 종료돼요.\n\n보완이 필요한 서류\n${list || `- ${STAFF_INPUT_MARK}: 보완이 필요한 서류와 사유를 적어 주세요]`}\n\n서류를 준비하신 뒤 상속예금 사전접수를 새로 신청해 주세요.`
  if (outcome === 'REQUIREMENT_FAILED') return `사전검토 결과 신청 요건을 충족하지 않아 이번 사전접수는 여기서 종료돼요.\n\n사유: ${STAFF_INPUT_MARK}: 요건 미충족 사유를 적어 주세요]`
  return list ? `보완이 필요한 서류\n${list}` : null
}

export function decisionGuide(data: C02StaffCase, outcome: string): string {
  if (data.finalDecision) return data.finalDecision.notification ? '검토 결과가 저장되고 고객에게 안내되었습니다.' : '검토 결과가 저장되었습니다.'
  if (data.caseView.caseStatus === 'IN_REVIEW') return outcome === 'DOCUMENT_INSUFFICIENT' ? '추가로 필요한 서류와 보완 사유를 고객에게 안내해 주세요.' : '서류 원본과 상속관계를 확인한 후 검토 결과를 선택해 주세요.'
  if (data.precheck?.agentRunStatus === 'NOT_RUN') return '먼저 사전점검을 실행해 주세요.'
  if (data.precheck?.agentRunStatus === 'FAILED') return '자동 사전점검이 실패했습니다. 서류 원본을 직접 확인한 뒤 검토를 시작해 주세요.'
  return '자동 사전점검이 완료됐습니다. 제출된 서류를 확인한 뒤 검토를 시작해 주세요.'
}
