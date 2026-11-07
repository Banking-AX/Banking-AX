# 은행 업무기준 변경정보 연동 계약 v1

> 대상: Banking-AX 개발자, 은행 변경정보 Provider 구현자
> 구현: `core/policychange`, `ai-server/app/mock_bank_policy`, `ai-server/app/staff_agent/policy_impact.py`
> 특정 은행에 이 API가 있다는 뜻이 아닙니다. Mock Bank Policy Server용 교육 계약입니다.

## 1. 변경정보 피드

`GET /mock-bank/v1/policy-changes?after={cursor}&limit={1~200}`

| 헤더 | 방향 | 뜻 |
| --- | --- | --- |
| `X-Policy-Source-Token` | 요청 | 호출자 인증 |
| `X-Policy-Signature: sha256=<hex>` | 응답 | 응답 본문 바이트의 HMAC-SHA256. 불일치 시 응답 전체 거부 |

```json
{"sourceSystem": "MOCK_BANK_POLICY", "nextCursor": "1793000000000", "events": [{ "...": "아래 이벤트" }]}
```

## 2. 공통 이벤트

업무별 필드는 두지 않습니다. 업무 상세는 `detail.taskExtensions.<업무코드>`에만 넣습니다.

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `sourceSystem` | O | Provider의 출처와 일치해야 함 |
| `changeEventId` | O | 출처 내 유일. 재수신 판별 키 |
| `externalPolicyId` | O | 은행 측 기준 ID |
| `sourceVersion` | O | 표시용 버전 |
| `versionOrder` | O | 기준 ID별 증가 정수. 순서 판단 기준 |
| `previousVersion` | | 없으면 `null` |
| `policyCategory` | O | 은행 분류(자유값) |
| `changeType` | O | 아래 5종. 그 외는 격리 |
| `affectedTaskCodes` | O | 1개 이상. `^[A-Z][A-Z0-9_]{1,39}$` |
| `publishedAt`, `effectiveAt` | O | ISO-8601 UTC |
| `sourceStatus` | O | `PUBLISHED`, `WITHDRAWN` |
| `changeSummary` | O | 300자 이내 |
| `sourceReference.documentId` | O | `title`, `uri`는 선택 |
| `detail.after` | O | `before`, `addedCheckItems`, `taskExtensions`는 선택 |

| `changeType` | 내부 처리 |
| --- | --- |
| `INFORMATION_UPDATE`, `REVIEW_CRITERIA_CHANGE` | 직원 안내 반영 가능 |
| `ELIGIBILITY_RULE_CHANGE`, `DOCUMENT_REQUIREMENT_CHANGE`, `PROCEDURE_CHANGE` | 화면에 `적용 검토 및 개발 반영 필요` 표시 |

## 3. 수신 결과

| 결과 | 조건 | 저장 |
| --- | --- | --- |
| `ACCEPTED` | 정상, 등록 업무 1개 이상 | 검토 대상 |
| `STALE` | 현재 최신보다 낮은 `versionOrder` | 이력만 보존, 검토 불가 |
| `OUT_OF_SCOPE` | 등록 업무 없음 | 기록만, 검토 대상 아님 |
| `DUPLICATE` | 같은 이벤트·같은 내용 | 저장 안 함 |
| `QUARANTINED` | 검증 실패, 같은 ID·다른 내용, 업무 확장 오류 | 사유와 원문 보존, 업무 미반영 |

수신 이벤트의 `affectedTaskCodes` 중 미등록 코드는 `unmappedTaskCodes`로 직원에게 표시합니다.

## 3-1. 안내 표시 규칙

"최신 수신 버전"과 "승인된 유효 안내"는 다릅니다. 둘 다 업무 규칙 버전(`c02-g1` 등)과도 별개입니다.

| 상황 | 표시 |
| --- | --- |
| v2 승인, v3 미검토 | v2 안내 유지, v3는 `pending` |
| v2 승인, v3 미검토인데 은행에서 이미 시행 | v2 안내 유지 + `newerBankVersion=v3` 경고 |
| v2 승인, v3 승인·시행 예정 | v2와 v3 함께 표시, v3 시행 시 v2 종료 |
| v3가 `DEV_REQUIRED` 또는 `NO_ACTION` | v2 유지 |
| 철회 이벤트 시행 | 이전 승인 안내 즉시 종료, 철회 건은 `pending` |

철회는 더 큰 `versionOrder`, `sourceStatus=WITHDRAWN`인 새 이벤트로 보냅니다. 종료일 필드는 없습니다.
`caseId`를 주면 그 진행 중 Case와 관련된 변경만 사유(`reasonCodes`)와 함께 반환합니다. 종료된 Case에는 반환하지 않습니다.

## 4. 저장 구조

새 테이블 없이 `event_log`에 추가만 합니다. 수정·삭제는 없습니다.

| `entity_type` | `event_type` | `request_id`로 보장하는 것 |
| --- | --- | --- |
| `POLICY_CHANGE` | `POLICY_CHANGE_RECEIVED` | 출처+이벤트 ID당 1행 |
| `POLICY_CHANGE_QUARANTINE` | `POLICY_CHANGE_QUARANTINED` | 동일 원문당 1행 |
| `POLICY_SOURCE` | `POLICY_SYNC_COMPLETED`, `POLICY_SYNC_FAILED` | 요청당 1행, 커서 보관 |
| `POLICY_CHANGE_REVIEW` | `POLICY_AGENT_ANALYZED`, `POLICY_AGENT_ANALYSIS_FAILED` | 요청당 1행 |
| `POLICY_CHANGE_REVIEW` | `POLICY_REVIEW_DECIDED` | 변경 건당 결정 1행 |

## 5. 직원 API

모두 직원 JWT와 `user.can_pre_review=true`가 필요합니다.

| API | 역할 |
| --- | --- |
| `GET /api/v1/staff/policy-changes` | 목록과 수신 기록 |
| `GET /api/v1/staff/policy-changes/{id}` | 상세, 버전, 영향 후보, 분석, 이력 |
| `GET /api/v1/staff/policy-changes/notices?taskCode=` | 업무 화면용 안내. `approved`와 미반영 `pending` 분리 |
| `POST /api/v1/staff/policy-changes/sync` | 변경정보 수신 |
| `POST /api/v1/staff/policy-changes/{id}/analysis` | 직원 Agent 분석 |
| `POST /api/v1/staff/policy-changes/{id}/decision` | `NOTICE_APPROVED`, `DEV_REQUIRED`, `NO_ACTION` |

## 6. 내부 Agent API

`POST /api/v1/staff-agent/policy-impact` (`X-Staff-Agent-Token`)

- 입력: `change`, `mappedTaskCodes`, `registeredTasks`, `caseCandidates[{caseId, revision, caseStatus, taskCode, reasonCodes}]`. 고객 정보는 넣지 않습니다.
- 출력: `summary`, `changePoints`, `relatedTasks`, `recheckItems`, `customerNoticeHint`, `evidence`, `diff`, `method`(`LLM`/`RULE_FALLBACK`), `model`, `promptVersion`.
- Spring은 미등록 업무와 후보 밖 Case를 제거하고, 은행 지정 업무와 AI 제안을 `bankDesignated`로 구분합니다.

## 7. 확장 방법

| 상황 | 해야 할 일 | 건드리지 않는 것 |
| --- | --- | --- |
| 새 업무 추가 | `app.policy-change.registered-tasks`에 코드 추가 | 공통 API, 저장 구조, Agent |
| 후보를 더 좁히고 싶음 | `PolicyTaskProfile` 빈 1개 추가 (`C02PolicyProfile` 참고) | 공통 코드 |
| 업무 화면에 안내 표시 | `<PolicyNoticePanel taskCode="..." />` 1줄 | 공통 API |
| 은행 연동 방식 변경 | `PolicyChangeProvider` 구현 1개 추가, `app.policy-change.provider` 변경 | 수신 이후 전체 |
