import { describe, expect, it } from 'vitest'
import { c02DocumentGuidance, c02DocumentLabel, c02DocumentStatusLabel, c02OutOfScopeLabel, c02RequirementLabel, c02ValidationLabel } from './customerLabels'

describe('C-02 customer labels', () => {
  it.each([['DECEDENT_DETAILED_FAMILY_RELATION', '돌아가신 분의 가족관계증명서(상세)'], ['DECEDENT_BASIC_CERTIFICATE', '돌아가신 분의 기본증명서'], ['PASSBOOK_AND_SEAL', '통장 및 거래인감(도장)'], ['HEIR_IDENTITY_CARD', '상속인 신분증'], ['HEIR_SEAL_OR_SIGNATURE', '상속인 인감 또는 서명'], ['ABSENT_HEIR_ID_COPY', '방문하지 않는 상속인의 신분증 사본'], ['ABSENT_HEIR_SEAL_CERTIFICATE', '방문하지 않는 상속인의 인감증명서'], ['ABSENT_HEIR_POWER_OF_ATTORNEY', '방문하지 않는 상속인의 위임장'], ['DELEGATION_RECEIPT_NO', '위임장 접수번호']])('maps %s without exposing a document code', (code, label) => expect(c02DocumentLabel(code)).toBe(label))
  it('distinguishes policy labels and fallback', () => {
    expect(c02RequirementLabel('REQUIRED')).toBe('필수 서류'); expect(c02RequirementLabel('CONDITIONAL')).toBe('해당하는 경우 제출'); expect(c02RequirementLabel('VISIT_ONLY')).toBe('방문 시 확인')
    expect(c02DocumentGuidance('PASSBOOK_AND_SEAL')).toContain('온라인 제출은 필요하지 않습니다'); expect(c02DocumentLabel('UNKNOWN')).toBe('확인이 필요한 서류'); expect(c02DocumentStatusLabel('UNKNOWN')).toBe('서류 상태 확인 필요')
    expect(c02OutOfScopeLabel('MINOR_HEIR')).toContain('온라인 사전접수 대상이 아닙니다'); expect(c02ValidationLabel('HEIR_FIELDS_REQUIRED:x')).toBe('상속인의 성명과 관계를 입력해 주세요.')
  })
})
