# Banking-AX Agent 평가

실제 Agent 코드를 대상으로 분류 정확도, 근거성, 안전성, 응답 성능을 측정하는 자료다. 공유 DB, 18081, 18082, 9001, 5175는 사용하지 않는다. 9001은 `/openapi.json`만 읽었다.

**현재 상태 (2026-11-09):** 무료 측정과 직원 경로 실제 LLM 측정 완료 (승인 후 실행, 51회, $0.0544). 고객 의도 분류는 담당 팀원의 작업 완료 후 측정한다. `results/real_invalid_401_user_env_key`는 인증 실패 실행의 격리본이므로 인용하지 않는다.

| 문서 | 내용 |
|---|---|
| [AGENT_INVENTORY.md](AGENT_INVENTORY.md) | Agent 경로별 LLM·모델·프롬프트·RAG·스키마·연동 계약, 배포 버전 |
| [COST_AND_APPROVAL.md](COST_AND_APPROVAL.md) | 실제 호출 수·비용 한도와 필요한 승인 |
| [RESULTS.md](RESULTS.md) | PASS / FAIL / NOT_RUN 결과 |
| [FAILURE_ANALYSIS.md](FAILURE_ANALYSIS.md) | 실패 사례별 원인 |
| [REPORT_TABLE.md](REPORT_TABLE.md) | 최종보고서용 정량 표 |
| [PREDEPLOY_CHECKLIST.md](PREDEPLOY_CHECKLIST.md) | 배포 전 필수 확인사항 |
| [CHANGE_case-brief-6.md](CHANGE_case-brief-6.md) | 직원 Agent 개선 내용, 수정 전후 비교, 재측정 계획 |

## 데이터셋

| 파일 | 규모 | 기준 |
|---|---|---|
| `datasets/intent_cases.json` | 90건 | 기능명세서 CHAT-03/05/10, FLOW-01, 00-3. 프롬프트 예시 문장 제외 |
| `datasets/brief_cases.json` | 30건 (9개 시나리오) | `scripts/build_brief_cases.py`의 시나리오 정의 |
| `datasets/rag_questions.json` | 53건 (정답 있음 43, 권한 2, 근거 없음 8) | `ai-server/knowledge/curated` 원문의 절 |

정답은 구현 실행 결과를 보고 고치지 않는다. 작성자가 만든 초안이므로 사람의 검토가 아직 필요하다. 채점 결과 파일의 `humanVerdict`에 검토 결과를 적는다.

## 재현 방법

Git Bash에서 `docs/benchmark/agent/scripts`를 기준으로 실행한다. `PY=../../../../ai-server/.venv/Scripts/python.exe`, `PYTHONUTF8=1`.

무료 (외부 호출 없음):

```bash
$PY build_brief_cases.py        # 직원 의견 Case 생성
$PY selftest_and_cost.py        # 채점기 자체 시험, 비용 산정
$PY eval_rag.py                 # RAG 검색. 로컬 임베딩, 벡터 DB 복사본 사용
$PY eval_faults.py              # 장애 주입. provider 호출을 대체
$PY eval_rule_baseline.py       # LLM 없는 직원 의견 하한선
$PY contract_check.py           # Spring-FastAPI 경로 계약, 9001 비교
bash run_dryrun.sh              # 평가 서버 배관 시험. 점수가 아님
```

유료 (승인 후에만):

```bash
BANKING_AX_AGENT_EVAL_REAL_LLM=true RUN_NAME=real_new MAX_CALLS=150 BUDGET_USD=1.00 bash run_real.sh
```

고객 의도 분류를 빼고 직원 경로만 측정 (51회 호출, 의도 분류 코드가 작업 중일 때):

```bash
BANKING_AX_AGENT_EVAL_REAL_LLM=true RUN_NAME=real_new_staff ONLY=staff MAX_CALLS=55 BUDGET_USD=0.75 bash run_real.sh
```

평가 서버는 `127.0.0.1:19501`만 쓴다. Spring 자리에는 호출을 기록하는 가짜 게이트웨이를 둔다. 호출 수나 비용 한도에 닿으면 추가 호출을 막는다. 승인 환경변수가 없으면 `run_real.sh`는 시작하지 않는다 (동작 확인함).
