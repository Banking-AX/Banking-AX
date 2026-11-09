# 업무기준 변경 대응 직원 Agent 도입 타당성 검증

핵심 질문: 업무기준이 바뀌어 기존 접수 건에 영향을 줄 때, 직원 Agent가 직원의 확인을 빠르고 정확하게 돕고 잘못된 판단과 안내를 막는가.

| 산출물 | 위치 |
|---|---|
| 구현 연결표, 위험 매트릭스, 개선·차단 항목 | [AUDIT.md](AUDIT.md) |
| 독립 평가 정답셋 | `datasets/gold_policy_cases.json` |
| 무료 회귀·장애 시험 | `scripts/run_e2e.sh`, `scripts/e2e_policy.py` |
| 사용 전후 비교 실험 설계 (NOT_RUN) | [AB_EXPERIMENT_DESIGN.md](AB_EXPERIMENT_DESIGN.md) |
| 실행 결과, 지표, 보고서 문장 | [RESULTS.md](RESULTS.md), `results/e2e.json` |
| 실제 LLM 최소 호출 계획 | [PAID_PLAN.md](PAID_PLAN.md) |

상태: 무료 검증 완료, 유료 호출 0건, 사람 실험 미실시. 가상 업무기준과 합성 데이터의 제한된 환경이며 은행 운영 안전성 인증이 아니다.
