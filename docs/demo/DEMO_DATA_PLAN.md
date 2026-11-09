# Banking-AX 합성 시연 데이터 계획

## 원칙

- 대상은 `banking_ax_demo_*` 격리 스키마뿐이다. 공유 `banking_ax`, Case #7~#9, 실행 서버는 불변이다.
- 모든 사람·계좌·문서·정책은 교육용 합성 자료다. 신분증/공적 증명서/서명·인감 위조물은 만들지 않는다.
- 실제 CLOVA, LLM, SMS, 이메일은 호출하지 않는다. OCR/LLM fixture는 `MOCK_ONLY`로 화면·문서에 표시한다.
- Case 상태만 SQL로 위조하지 않는다. C-02 submission, staff review, clarification, reservation은 Spring service-backed integration fixture로 생성·검증한다.

## 계정과 권한 설계

| 별칭 | 역할 | 사용 시나리오 | 안전 조건 |
|---|---|---|---|
| customerA | 고객 | DEMO-01 | 정상 C-02 Case 소유 |
| customerB | 고객 | DEMO-02 | 질문 알림 수신자 |
| customerC | 고객 | DEMO-03 | 보완 대상 Case 소유 |
| customerD | 고객 | DEMO-05 | DRAFT 편집자 |
| staffA | 사전검토 직원 | DEMO-01~04 | `can_pre_review` + C-02 권한 |
| staffB | 지점 담당 | DEMO-06 | 해당 branch/slot/업무권한 |
| staffC | 수동 배정 직원 | DEMO-07~08 | 같은 지점 가용 직원 |
| staffDenied | 권한 없는 직원 | DEMO-10 | Case/branch access 403 검증 |

비밀번호 원문/해시는 이 문서와 fixture에 저장하지 않는다. 장기 브라우저 fixture가 승인될 때만 테스트 전용 환경변수로 주입한다.

## 시나리오 시작 상태

| ID | 시작 상태 | 실제 관계/검증 | 자동화 근거 | 시연 중 바뀌는 값 |
|---|---|---|---|---|
| DEMO-01 | `SUBMITTED`, `READY_FOR_STAFF` | 2 heirs, 6 required uploads, 2 visit-only; MOCK OCR 3 name matches + account staff-required | document review / brief tests | decision → notification → reservation |
| DEMO-02 | `WAITING_CUSTOMER`, OPEN clarification | 질문 1건·진술 1건·답변 없음 | clarification test | answer → revision+1 → recheck/brief |
| DEMO-03 | submitted, staff document check pending | 실제 파일 존재와 수동 보완 사유 | document review test | NEEDS_SUPPLEMENT + history |
| DEMO-04 | submitted, mock OCR mismatch | file/revision-bound mismatch only | POA verification tests | employee sees mismatch/manual checks |
| DEMO-05 | DRAFT | heirs/files linked to slots | C02 intake tests | visit/delegation change invalidates slot binding |
| DEMO-06 | `REVIEW_PASSED` + notified | open branch slots, capable staff | VisitReservationLocalMySqlIT | automatic confirm + notification |
| DEMO-07 | `NEEDS_MANUAL_ASSIGNMENT` | no automatic rank; eligible free staff exists | VisitReservationManualLocalMySqlIT | manual confirm |
| DEMO-08 | confirmed + pending | alternate slot/full slot/expired pending | same | change success/fail, expiration |
| DEMO-09 | mock v2 approved/v3 pending | policy feed/event log only | PolicyChangeLocalMySqlIT | employee decision/notice selection |
| DEMO-10 | mixed inbox states | owner/branch/reviewer access boundaries | C-02 and reservation tests | 403 denied access |

`fixtures/demo-scenarios.json` is the machine-readable source for this table. All scenarios currently have `presentationReady=false`: the repo has reproducible service-backed tests, but not an approved persistent/API-only browser demo seeder. That distinction prevents test records from being presented as real completed cases.

## DB relation checklist

`user → workflow → work_item → service_case → case_revision`, `heir`, `required_document → document_file → document_extraction`, `agent_review_run → review_item`, `clarification`, `case_statement`, `staff_review`, `notification`, `visit_reservation`, `event_log` are checked through the focused integration suites. Policy change records use the existing `PolicyChangeStore` event-log representation; no unverified new policy table is assumed.

## Fixture documents

| Fixture | Use | Constraint |
|---|---|---|
| `C02DemoSeeder.DemoPdf` synthetic PDF | upload/file lifecycle tests | not a government/identity document |
| power-of-attorney OCR JSON fixtures | DEMO-01/04 test only | explicit `MOCK_ONLY`; no CLOVA claim |
| document-review note | DEMO-03 | employee-entered reason, not automatic document authenticity result |

## Re-run / cleanup

Run `scripts/demo/seed-isolated.ps1 -Mode BuildAndVerify` only with `DEMO_DB_URL` matching `banking_ax_demo_*` or the existing guarded `banking_ax_t2_verify`. Tests reset only that guarded schema. There is no cleanup command for shared data because shared data is never created. A durable browser set needs a separately approved API-driven seeder and a test user credential injection contract.
