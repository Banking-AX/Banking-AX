# Agent 경로 구분

코드 기준일 2026-11-09. 모델은 모두 `OPENAI_MODEL` 값 `gpt-4.1-mini`, temperature 0이다.

| 경로 | 진입점 | LLM | 프롬프트 버전 | RAG | 출력 스키마 | Spring 연동 | 실패 시 |
|---|---|---|---|---|---|---|---|
| 고객 의도 분류 | `POST /api/v1/customer-agent/messages/start` | 사용. timeout 18초, 재시도 1회 | **없음** | 없음 | `LlmIntentOutput` (strict JSON Schema + Pydantic 일관성 검증) | `HttpCustomerAgentClient`, `X-Customer-Agent-Token`, 제한 20초 | 502 또는 503, Tool 호출 없음 |
| A/B/C 업무 라우팅 | `_non_start_outcome` | 미사용 (규칙) | 해당 없음 | 없음 | `CustomerNaturalStartResponse` | `/api/v1/tools/workflows/start`, `/tools/loan-inquiry` | 502 또는 503 |
| 직원 사전점검 | `POST /api/v1/staff-agent/check` | **미사용 (규칙)** | 해당 없음 | keyword, Top 3 | `CheckResponse` | `StaffAgentHttpClient`, `X-Staff-Agent-Token` | `FAILED_NEEDS_STAFF` |
| 직원 검토 의견 | `POST /api/v1/staff-agent/case-brief` | 사용. timeout 18초, 재시도 없음 | `case-brief-5` | keyword, Top 4 | `Brief` | `StaffBriefHttpClient`, `agent_review_run(STAFF_BRIEF)` | `RULE_FALLBACK` |
| 업무기준 영향 분석 | `POST /api/v1/staff-agent/policy-impact` | 사용. 위와 동일 | `policy-impact-2` | keyword, Top 3 | `Interpretation` | `PolicyAgentHttpClient` | `RULE_FALLBACK` |
| 직원 업무기준 질의 | `POST /api/v1/staff-agent/policy-question` | 사용. 근거 없으면 호출 안 함 | `policy-question-1` | keyword, Top 4 | `Answer` | `StaffPolicyQuestionHttpClient` | `EVIDENCE_ONLY` 또는 `NO_EVIDENCE` |
| 고객 업무 안내 | `POST /api/v1/rag/task-guide`, `/rag/answer` | 사용 | 없음 | hybrid | `GuideLlmOutput` / 자유 텍스트 | 프론트 프록시 | 근거 발췌 또는 오류 |

**규칙 기반 사전점검 결과는 LLM 점수에 포함하지 않는다.** 기존 24/24는 규칙 분기 회귀검증이다.

입력 경계: 직원 검토 의견에는 성명, 생년월일, 계좌번호, 파일명, OCR 원문, 직원이 쓴 사유, 고객 진술이 들어가지 않는다 (`C02StaffBriefFacts`). 서류 본문을 LLM이 읽는 경로는 없으므로 명세 STAFF-18의 의미적 불일치 분석은 구현되어 있지 않다.

## 버전

| 대상 | 값 |
|---|---|
| 브랜치 | `feat/t2-b02-c02-e2e`, HEAD `26d4f68` |
| origin/main | `9b732cc`. 이 브랜치는 7커밋 앞서고 18커밋 뒤처짐 |
| 작업 트리 | 미커밋 변경 있음. 평가는 작업 트리 기준 |
| 18081 | pid 33516, 2026-11-09 재시작, 작업 트리 클래스로 실행. JAR 없음 |
| 9001 | pid 57408, 2026-11-08 18:12 시작. `ai-server/app` 최종 수정 16:54이고 OpenAPI 경로가 소스와 일치 |
| RAG 인덱스 | `chunks.json` 242조각, 문서 버전 필드 없음, `RAG_MODE=poc` |
