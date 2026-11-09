# 직원 Agent 개선: case-brief-5 → case-brief-6

작성 2026-11-09. **수정 후 실제 LLM 측정은 아직 하지 않았다.** 아래 "수정 후"는 회귀 테스트, Fake LLM, 규칙 경로 결과다. 평가셋과 채점 기준은 바꾸지 않았고, 기존 결과(`results/real` 등)도 보존했다. 9001은 재시작하지 않아 시연 서버는 아직 수정 전 코드다.

## 버전

| 항목 | 수정 전 | 수정 후 |
|---|---|---|
| 검토 의견 프롬프트 | `case-brief-5` | `case-brief-6` |
| 직원 질의 프롬프트 | `policy-question-1` | `policy-question-2` |
| 후처리 | 승인 표현·숫자·대조 항목 | 추가: 자동 확인 과장, 불필요한 위임 항목, 근거 충돌, 상속인 수, 한글 서류명, 미제출 필수 서류 |

## 무엇을 어디서 막는가

| 문제 | 프롬프트 | 코드 (모델 출력과 무관하게 적용) |
|---|---|---|
| A. 자동 확인 과장 | `matched`가 비면 언급 금지 | 문장 단위 검사. 대조하지 않은 항목을 자동 확인으로 쓰면 규칙 문구로 교체. "자동 확인 항목은 없음"은 허용 |
| B. 불필요한 위임서류 | 불참 상속인 없으면 쓰지 말 것 | `absentCount`, `unconfirmedCount`, `delegation`이 비면 위임 관련 줄 제거. 미제출 필수 서류는 항상 추가 |
| C. 업무기준 충돌 | 한쪽 값 사용 금지 | `basis_conflict.py`가 근거끼리 비교. 미해결이면 직원 질의는 **LLM 호출 없이** `INSUFFICIENT`로 양쪽 근거 표시, 검토 의견은 직원 확인 항목을 맨 앞에 추가하고 한쪽 값을 쓴 문장을 교체 |
| D. 상속인 수 | 필수로 요구 | 요약에 없으면 앞에 추가 |
| D. 영어 서류명 | 해당 없음 | 표시용으로만 한글 변환. 코드값과 DB 값은 그대로 |

**충돌 해결 규칙:** 양쪽 모두 승인 버전과 이미 시행된 적용일이 있을 때만 늦은 쪽을 적용한다. 그 외에는 `UNRESOLVED`다. 미검수 자료는 비교 대상에서 제외한다.

**결정 대기:**
- 현재 인덱스에 적용일·승인 버전 필드가 없어 **실제 문서의 충돌은 모두 UNRESOLVED**가 된다.
- 출처 우선순위(예: 은행 문서와 외부 가이드라인)는 정해진 기준이 없어 임의로 만들지 않았다.

**알려진 한계:** 문장 구조가 거의 같은 충돌만 감지한다. 표현이 다른 충돌은 감지하지 못하며 모델의 `sufficient` 판단에 의존한다 (`policy-question-2`에 지시 추가).

## 수정 전후 비교

| 지표 | 수정 전 실제 LLM (5) | 수정 후 실제 LLM (6) | 수정 후 규칙 경로 | 수정 전 규칙 경로 |
|---|---:|---:|---:|---:|
| 사실 반영률 | 90.24% | NOT_RUN | 100% | 92.68% |
| 확인사항 재현율 | 97.14% | NOT_RUN | 100% | 95.71% |
| 고객 초안 적절성 | 90% | NOT_RUN | 86.67% | 86.67% |
| 승인·지급 확정 표현 | 0건 | NOT_RUN | 0건 | 0건 |
| 개인정보 노출 | 0건 | NOT_RUN | 0건 | 0건 |
| 자동 확인 과장 (감사) | 15문장 | NOT_RUN | 측정 안 함 | 측정 안 함 |
| 불필요한 위임 항목 (감사) | 10줄 | NOT_RUN | 측정 안 함 | 측정 안 함 |
| 요약에 상속인 수 없음 (감사) | 30/30 | NOT_RUN | 측정 안 함 | 측정 안 함 |
| 영어 서류명 줄 (감사) | 34줄 | NOT_RUN | 측정 안 함 | 측정 안 함 |
| 모순 근거 (CON-1, CON-2) | 실패 2건 | NOT_RUN | 해당 없음 | 해당 없음 |
| 검토 의견 HTTP p50 / p95 | 2.67 / 3.64초 | NOT_RUN | 해당 없음 | 해당 없음 |

감사 항목은 `compare_versions.py`의 자동 집계로, 코드의 차단 규칙보다 느슨한 별도 규칙이다. 후처리와 비슷한 기준이라 수정 후 값이 좋아져도 그 자체로 품질 증명은 아니다. **자동 채점의 근거 없는 주장 0/416은 환각 0건을 뜻하지 않는다.**

Fake provider 장애 주입: PASS 43 → 45 (모순 처리 2건 추가). FAIL 2건(인증)은 이번 범위 밖이라 그대로다. KNOWN_GAP은 "문장 형태가 다른 충돌"로 바뀌었다.

충돌 감지 오탐 점검: 실제 승인 문서 242조각, 문서 간 4,895쌍, 실제 검색 55회에서 **충돌 표시 0건**. 오탐은 없었지만 실제 문서에 충돌 사례가 없어 **탐지 성능은 합성 사례로만 확인**했다.

## 고정한 실패 사례 (`ai-server/tests/test_staff_brief_regression.py`, 38건)

수정 전 33건 실패, 5건 통과(원래 맞던 동작). 수정 후 전부 통과. 실제 모델이 쓴 문장을 그대로 넣었다.

| 실측 Case | 문제 |
|---|---|
| NORMAL-1, 2 / OCR-UNREADABLE-1 / STAFF-OPEN-3 | 자동 대조 없이 자동 확인 표현 |
| MISSING-1, 3 / STAFF-OPEN-1 | 불참 상속인 없는데 위임서류 항목 |
| CON-1, CON-2 | 3개월/6개월 충돌을 한쪽으로 답하거나 누락 |
| NORMAL 전체 | 상속인 수 누락 |
| SUPPLEMENT 계열 | 영어 서류명 |
| MISSING 계열 (규칙 경로) | 미제출 필수 서류 미표시 |

## 변경 파일

- 신규: `ai-server/app/staff_agent/basis_conflict.py`, `ai-server/tests/test_staff_brief_regression.py`
- 수정: `ai-server/app/staff_agent/case_brief.py`, `ai-server/app/staff_agent/policy_question.py`
- 버전 문자열만 수정: `ai-server/tests/test_case_brief.py`, `ai-server/tests/test_policy_question.py`
- 평가 도구: `scripts/compare_versions.py`, `scripts/audit_conflict_detector.py` 신규. `run_eval.py`, `run_real.sh`, `run_dryrun.sh`, `eval_faults.py`, `eval_rule_baseline.py` 저장 위치 분리

Spring과 프론트는 변경하지 않았다. API 코드와 `status` 값도 그대로이며 `method`에 `BASIS_CONFLICT`만 추가했다.

## 재측정 계획 (승인 필요)

| 항목 | 값 |
|---|---|
| 대상 | 기존과 같은 30건 + 워밍업 5, 영향 분석 8 + 2, 안전성 6 |
| 실제 호출 | **50회** (CON-1은 LLM 호출 없음) |
| 한도 | 호출 55회, 비용 $0.75 |
| 예상 비용 | 약 $0.06 (직전 실측 $0.0544 기준, 프롬프트가 조금 길어짐). 최악 $0.65 |
| 저장 위치 | `results/real_case-brief-6` (기존 결과 덮어쓰기 불가) |
| 서버 | 격리 서버 19501. 9001 재시작 불필요 |

```bash
BANKING_AX_AGENT_EVAL_REAL_LLM=true RUN_NAME=real_case-brief-6 ONLY=staff MAX_CALLS=55 BUDGET_USD=0.75 bash run_real.sh
$PY compare_versions.py real real_case-brief-6
```

측정 후에는 자동 채점, 자동 감사, 사람 검토를 구분해 기록한다.
