# VisitPort v1 — T2 Phase 0

## 선행조건과 호출

`POST /internal/visits`는 Spring만 구현·확정한다. Agent는 후보 순위만 제안할 수 있다. 요청은 `caseId`, `branchSlotId`, `requestId`, `expectedCaseRevision`을 포함한다.

Spring은 고객 소유권, `staff_review.result=PROCEED`, `service_case.case_status=REVIEW_PASSED`, `result_notified_at` 존재, 방문 필요, 슬롯 상태/정원/24시간 규칙을 트랜잭션에서 다시 검사한다. 제출 직후에는 `RESERVATION_NOT_ALLOWED`로 거절한다.

## DTO와 상태

```json
{"caseId":200,"branchSlotId":701,"requestId":"visit-200-1","expectedCaseRevision":1,
 "status":"SELECTED","recommendedStaffIds":[31,32]}
```

상태는 DDL의 `SELECTED`, `PENDING_ASSIGNMENT`, `CONFIRMED`, `NEEDS_MANUAL_ASSIGNMENT`, `CANCELED`, `EXPIRED`, `COMPLETED`만 쓴다. `CONFIRMED`는 Spring이 직원/가용성/중복/업무코드/업무량을 재검증한 뒤에만 가능하다. 추천은 업무코드와 현재 업무량만 사용하며 경험 점수는 금지한다.

## Fake와 테스트

Fake는 결과 통지 완료 fixture에서만 `SELECTED`를 반환하고 슬롯을 점유하거나 직원을 확정하지 않는다. 미통지 Case는 `RESERVATION_NOT_ALLOWED`로 거절한다(T-01). 실제 락·정원·배정 및 24시간 변경/취소는 Spring 프로젝트 병합 후 구현한다.
