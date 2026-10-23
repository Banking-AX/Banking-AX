# 직원 Agent `register_check` / 결과서 v1 — T2 Phase 0

## 호출과 권한

Spring은 제출된 C Case에 대해 `caseId + revision + checkType`을 가진 내부 요청을 FastAPI 직원 Agent에 보낸다. 호출자는 Spring 서비스 계정이어야 하며, Agent는 DB를 직접 쓰지 않는다. Spring이 최신 revision과 권한을 다시 확인한 뒤 `agent_review_run`/`review_item`을 저장한다.

Phase 0 Python 경계는 `CheckRegistry.register(task_code, check_type, handler)` 및 `StaffCheckExecutor.run(CheckRequest)`이다. HTTP Router는 T1의 고객 Router와 독립적으로 후속 등록한다.

```json
{
  "requestId": "precheck-c02-200-1",
  "caseId": 200,
  "revision": 1,
  "taskCode": "INHERITANCE_DEPOSIT",
  "checkType": "STAFF_PRECHECK",
  "caseSnapshot": {"caseStatus":"SUBMITTED", "reviewStatus":"PENDING"}
}
```

`requestId`는 같은 요청 본문에만 재사용할 수 있다. 같은 `(caseId, revision, checkType)`은 DDL의 `agent_review_run` unique 제약으로 중복 저장하지 않는다. 구 revision 응답은 최신 결과를 덮어쓰지 않는다.

## 결과 DTO

```json
{
  "caseId": 200, "revision": 1, "checkType": "STAFF_PRECHECK",
  "status": "SUCCEEDED", "reviewStatus": "READY_FOR_STAFF",
  "reviewItems": [{
    "source":"AGENT_SEMANTIC", "category":"VISIT_ATTENDANCE",
    "result":"NEEDS_STAFF_REVIEW", "basisDocId":"C02-01",
    "evidence":{"policyEvidence": []}, "message":"직원 확인 필요"
  }],
  "error": null
}
```

`source`는 DDL의 `SPRING_RULE`, `AGENT_SEMANTIC`, `PRE_SUBMISSION`만 쓴다. 결과서는 직원 판정(`staff_review.result`)이 아니며 `PROCEED`를 만들지 않는다. 정상/고객 추가 확인/직원 확인/분석 실패는 각각 `READY_FOR_STAFF`, `WAITING_CUSTOMER`, `READY_FOR_STAFF`, `FAILED_NEEDS_STAFF`의 Agent 상태로 표현한다.

## 실패·시간초과·Fake

`TIMEOUT`, `CASE_NOT_FOUND`, `STALE_REVISION`, `DUPLICATE_REQUEST_CONFLICT`, `CHECK_NOT_REGISTERED`, `RAG_UNAVAILABLE`는 `FAILED_NEEDS_STAFF`와 수동 검토 경로로 반환한다. 자동 통과는 금지한다.

Phase 0 C-02 Fake는 상속인 방문 진술의 불일치 후보를 반환한다. RAG 결과가 없거나 `verified=false`면 해당 사실을 직원 확인 항목으로 추가한다.

## 테스트

- 동일 requestId 재호출은 같은 결과를 반환
- requestId 충돌 및 구 revision은 거절
- C-02 Fake 결과는 최종 `PROCEED`가 아님
- RAG 무근거/미검증 결과가 자동 통과를 만들지 않음
