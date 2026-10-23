# ERD v1.7 검토 가이드 — 그림보다 업무 흐름부터 보기

> **32개 테이블 / 실제 구현 DDL `01_schema_mysql8.sql` 기준**. ERDCloud에서 컬럼이 너무 많이 보일 경우, *업무 시나리오 하나에 관여하는 테이블만* 선택·배치하여 검토한다. 아래는 구현 검토용 관계 설명이며, 모든 테이블이 직접 FK로 연속 연결된다는 뜻은 아니다.

## 1. 제일 먼저 볼 5가지 관계

| 흐름 | 테이블 | 무슨 질문에 답하나? |
| --- | --- | --- |
| 고객 업무 생성 | `user → workflow → work_item` | 누가 어떤 업무를 어떤 순서·상태로 요청했나? |
| 고객 C 접수 | `work_item → service_case → case_revision` | C 신청이 언제 제출됐고 어떤 버전의 신청정보를 검토했나? |
| 제출서류 | `work_item → required_document → document_file → document_extraction` | 어떤 서류가 필요했고, 어느 파일 버전을 어떤 방식으로 읽었나? |
| 직원 Agent·직원 확정 | `service_case → case_revision → agent_review_run / review_item / staff_review` | AI가 무엇을 분석했고, 사람이 무엇을 확정했나? |
| **결과 통지 후 방문 예약** | `staff_review(PROCEED) → service_case → visit_reservation → branch_slot / staff_slot_availability` | 예약 가능한 Case인가, 좌석·직원 시간 중복 없이 확정됐나? |

**중요:** `staff_review(PROCEED)` → `visit_reservation`은 **Spring 업무 규칙상의 선후관계**이며 두 테이블 사이에 직접 FK가 있는 것은 아니다. 예약 테이블은 `case_id`로 `service_case`에 연결되고, Spring이 `staff_review`와 결과 통지 상태를 검사한다.

## 2. 전체 32개 테이블 — 역할과 검토 초점

| 영역 | 테이블 | 업무상 역할 | 검토할 핵심 컬럼/관계 |
| --- | --- | --- | --- |
| ① 사용자/영업점 | `branch` | 모의 지점 정보 | `branch_code` 유일 |
| ① 사용자/영업점 | `user` | 고객·직원·관리자 계정 | `role`, `branch_id`, `can_pre_review`; 고객·직원 접근권한 |
| ① 사용자/영업점 | `staff_task_capability` | 직원별 처리 가능 C업무 코드 | `staff_id`, `task_code` UNIQUE |
| ② 채팅 | `chat_room` | 고객 대화방 | `customer_id` |
| ② 채팅 | `chat_message` | 고객/AI 메시지(7일 보관) | `chat_room_id`, `seq`, `sent_at` |
| ③ 업무 흐름 | `workflow` | 한 요청에 포함된 업무 묶음 | `customer_id`, `chat_room_id`(nullable), `status`, `version` |
| ③ 업무 흐름 | `work_item` | A/B/C 개별 실행 단위 | `workflow_id`, `task_code`, `sequence_no`, `resume_step`, `status`, `draft_data`, `last_activity_at` |
| ④ A/B 결과 | `task_result` | A 조회·B 실행 결과·변경 전후 | `work_item_id`, `before_value`, `after_value`, `rule_version`, `detail` |
| ④ 동의 | `consent_record` | B 변경 전 동의·C 정보동의 | `work_item_id`, `consent_type`, `agreed_at` |
| ⑤ Mock 금융 | `deposit_account` | 고객 계좌 | `customer_id` |
| ⑤ Mock 금융 | `account_transaction` | 입출금 내역 | `account_id` → `deposit_account` |
| ⑤ Mock 금융 | `loan` | A-02 조회, B-01 변경, C-01 채무조정 대상 | `customer_id`, `repayment_order`, 연체·원금 관련 Mock 필드 |
| ⑤ Mock 금융 | `housing_savings_account` | B-02 해지 대상·적용 이율 구분 | `customer_id`, `status`, `applied_rate_type` |
| ⑥ C-02 Mock | `decedent` | 피상속인 정보·예금 합계 | `deposit_total` 및 JSON 예금 목록(별도 예금 테이블 불필요한 현 범위) |
| ⑥ C-02 Mock | `delegation_receipt` | 모의 위임 접수번호 | `receipt_no`, `decedent_id` |
| ⑦ C업무 | `service_case` | 고객 신청·직원 결과·AI 점검 2개 상태 | `work_item_id` UNIQUE, `case_status`, `review_status`, `revision`, `loan_id`/`decedent_id`, `result_notified_at` |
| ⑦ C업무 | `heir` | Case별 상속인 및 방문/위임 정보 | `case_id`, `person_ref` UNIQUE, `visit_status` |
| ⑦ C업무 | `case_revision` | 신청정보·유효 서류의 버전별 스냅샷 | `(case_id, revision)` UNIQUE; 과거 버전 불변 |
| ⑦ C업무 | `case_statement` | 채팅과 분리한 최소 진술 요약(30일) | `case_id`, `revision`, `expires_at` |
| ⑧ 제출서류 | `document_type` | 서류 코드/출처·OCR 허용 정책 | `source_kind`, `source_verified`, `ocr_enabled` |
| ⑧ 제출서류 | `required_document` | 업무별 요구서류 1건·사람별 조건 | `work_item_id`, `document_type_code`, `heir_id`(nullable) |
| ⑧ 제출서류 | `document_file` | 실제 업로드 파일의 버전/저장위치 | `required_document_id`, `version`, `is_latest` |
| ⑧ 제출서류 | `document_extraction` | 파서/OCR 결과·실패·비대상 | `document_file_id`, `method`, `status`, `fields` |
| ⑨ 직원 Agent | `agent_review_run` | Case revision별 점검 실행 | `(case_id, revision, check_type)` UNIQUE |
| ⑨ 직원 Agent | `review_item` | 정형 규칙·의미 불일치 항목과 근거 | `case_id`, `revision`, `source`, `document_file_id` |
| ⑨ 직원 Agent | `clarification` | 고객에 대한 추가 사실 확인 질문·답변 | `case_id`, `requested_revision`, `status` |
| ⑨ 직원 | `staff_review` | **사전검토 직원**의 검토 시작·최종 결과 | `(case_id, revision)` 참조, `staff_id`, `result`, `decided_at` |
| ⑩ 예약 | `branch_slot` | 지점별 예약 시간·정원 | `branch_id`, `start_at`, `capacity`, `reserved_count` |
| ⑩ 예약 | `staff_slot_availability` | 슬롯별 직원 가용·배정 수 | `staff_id`, `branch_slot_id`, `assigned_count` |
| ⑩ 예약 | `visit_reservation` | Case별 현재 방문 예약·AI 추천/최종 배정 | `case_id` UNIQUE, `branch_slot_id`, `assigned_staff_id`, `status` |
| ⑪ 부가 | `notification` | 고객 웹 알림·B/C 이메일 발송 상태 | `case_id`/`work_item_id` 및 `task_result_id`, `dedup_key` |
| ⑪ 부가 | `event_log` | 상태 변경/예약 재배정·감사·멱등 이벤트 | `entity_type`, `entity_id`, `event_type`; 다형 FK 없음 |

## 3. 이 5개 시나리오로 ERD를 검토하면 된다

### 사례 A — “거래내역 조회하고 채무조정도 접수할게요”

`chat_room`에 대화가 저장되고 `workflow` 하나에 `work_item` 두 개(A-01, C-01)가 생성된다. 한 번에 `ACTIVE`는 하나만 가능. A 조회 결과는 `task_result`에, C 접수는 `service_case`에 저장된다. C가 제출되면 WorkItem은 `HANDED_OFF`이지만 Case 검토는 계속된다.

**질문:** Workflow 하나가 여러 WorkItem을 가질 수 있나? Case 없이 A가 완료될 수 있나? 답은 둘 다 '예'.

### 사례 B — “형은 해외에 있어서 방문 못 해요”

C-02 `service_case` → `heir`별 방문/위임 정보 → `required_document.heir_id`로 서류 연결 → `document_file` 업로드. 고객이 같은 Case에서 답변·파일을 교체하면 `case_revision`이 증가하고 `agent_review_run`/`review_item`은 새 버전으로 실행된다.

**질문:** 과거 버전 결과를 덮어쓰지 않는가? 미응답 `UNCONFIRMED_ABSENT`를 법적 최종 불참으로 저장하지 않는가?

### 사례 C — “서류를 냈는데 직원 검토 전 취소”

`service_case`는 `SUBMITTED`지만 `staff_review.started_at` 이전이면 고객 접수 취소 가능. 직원 검토 시작과 취소는 트랜잭션으로 경합 제어. 이미 직원 검토가 시작되면 취소 요청은 실패한다.

**질문:** Agent가 이미 실행됐더라도 사람 직원이 검토를 시작하지 않았다면 취소 가능한가? **가능**하되 취소 후 지연된 Agent 결과가 상태를 되돌리면 안 된다.

### 사례 D — “직원 검토 결과 통지 후 방문 예약”

직원이 `staff_review.result=PROCEED` 확정하고 `service_case.case_status=REVIEW_PASSED`, 결과 알림이 기록되면 고객이 `branch_slot` 선택. `visit_reservation`은 그때 생성된다. AI는 가용한 `staff_task_capability`·`staff_slot_availability` 후보 중 업무량 기준으로 추천하고 Spring이 다시 검사해 `CONFIRMED`로 확정한다.

**질문:** 제출만 했는데 예약이 생기는가? **아니오.** AI가 추천만 했는데 정원이 차는가? **아니오.** `PENDING_ASSIGNMENT`에서는 Spring이 슬롯 정원을 잠시 점유할 수 있다.

### 사례 E — “예약 23시간 전에 취소할게요”

현재 슬롯 `branch_slot.start_at`까지 24시간 미만이면 서버가 취소/변경을 거부한다. 25시간 전에는 허용하며 슬롯·직원 배정 카운터를 한 트랜잭션에서 반납한다. 외부 API가 중복 호출되더라도 정원이 두 번 감소하면 안 된다.

**질문:** 타임존은? UTC 저장, API 사용자 안내 KST. 변경 이벤트는 `event_log`에 기록된다.

## 4. ERDCloud 실무 검토 순서

1. 먼저 `workflow`·`work_item`·`service_case` **세 개만 화면 중앙**에 놓고 각각 1:N, 1:0..1 관계를 이해한다.
2. 오른쪽에 `case_revision`·`staff_review`를 놓고 직원 검토 후 예약이 가능하다는 **업무 규칙**을 확인한다.
3. 그다음 `visit_reservation`·`branch_slot`·`staff_slot_availability`·`staff_task_capability`·`user`를 배치한다.
4. 아래쪽에 `required_document`·`document_file`·`document_extraction`·`heir`를 배치한다. **서류는 Case 직접 소유가 아니라 WorkItem 소유**라는 점을 확인한다.
5. 맨 마지막에 `chat_message`·`task_result`·`notification`·`event_log` 및 Mock 금융 테이블을 본다.

**관계를 읽을 때는 선의 개수보다 이 세 가지를 묻는다:**

- 누가 소유하는가? (FK, 권한 검사)
- 어느 시점에 생기는가? (제출 전/후, 직원 확정 전/후)
- 데이터가 바뀌었을 때 기존 기록을 어떻게 보존하는가? (revision, version, event_log, TTL)

## 5. 다이어그램만 보고 바꾸면 안 되는 것 / 구현 전에 확인할 것

- `work_item`의 컬럼은 그림에서 빠져도 실제 DDL에 `sequence_no`, `resume_step`, `version`이 있다.
- `service_case.loan_id`, `decedent_id`는 **실제 FK**가 있다.
- 피상속인 예금은 현재 `decedent`에 합계/JSON으로 보관하고, 금융 원장 기능 확장 시에만 테이블 분리를 검토한다.
- `task_result`는 이미 변경 전후 JSON 컬럼이 있다.
- `staff_review` → `case_revision` 연결은 `(case_id, revision)` **복합 FK**다.
- `visit_reservation`은 Case별 **현행 1건**, 이전 예약의 변경 이력은 `event_log`다.
- 직원의 **예약 전 중앙 사전검토 권한**, 슬롯 운영정책, C-01 방문 개방 조건, 실제 OCR 허용 양식은 아직 팀 검토 항목이다. 임의로 은행 내부 정책을 가정하지 않는다.
- **실제 DB 생성은 `01_schema_mysql8.sql`**, ERDCloud Import는 `02_ERDCloud_IMPORT.sql`. 두 파일의 CHECK·생성 컬럼 차이를 바꾸려다 혼동하지 않는다.
