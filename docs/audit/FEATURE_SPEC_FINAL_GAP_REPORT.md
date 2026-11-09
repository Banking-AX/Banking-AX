# 기능명세서 v1.7.1 구현 감사 — 2026-11-08

## 감사 기준과 한계

| 확인 자료 | 용도 |
|---|---|
| `docs/00_기능명세서_v1.7.1.md` | 기능 ID와 수용 기준의 정본 |
| `docs/01_schema_mysql8.sql`, Flyway V1~V14 | 테이블·제약·최신 적용 경계 |
| `docs/contracts/**`, `docs/04_UI_화면목록.md`, `README.md`, `AGENTS.md` | API/UI/소유권 보조 근거 |
| Spring/React/FastAPI 소스와 테스트 | 호출 경로 및 자동 테스트 근거 |

명세서에서 추출한 기능 ID는 **198개**다(A 2, AXE 8, B 2, C 2, DOC 22, HIST 11, MOCK 16, NOTI 24, RAG 11, REQ 19, STAFF 22, SYS 24, T 21, VISIT 14). 이 문서는 코드 탐색과 자동 테스트 결과를 구분한다. 브라우저 E2E와 실제 CLOVA/실제 LLM 호출은 이번 감사에서 실행하지 않았다.

문서 충돌: v1.7.1의 NOTI-07~09/24는 SMTP를 요구하지만, 현재 팀 정책은 실제 이메일 발송을 사용하지 않고 웹 알림만 사용한다. 이는 `OUT_OF_SCOPE (team decision pending written approval)`로 분리했으며 SMTP를 새로 구현하지 않았다.

## 전체 기능 카탈로그 상태

완전한 ID 목록은 명세서에서 매번 재현 가능하게 추출한다.

```powershell
rg -o --no-filename "\\b(?:SYS|A|B|C|DOC|REQ|STAFF|NOTI|HIST|VISIT|RAG|AXE|MOCK|T)-[0-9]{2}\\b" docs/00_기능명세서_v1.7.1.md | Sort-Object -Unique
```

| 기능 묶음 | 상태 | 구현/근거 | 감사 결론 |
|---|---|---|---|
| A-01, B-02, C-02 | IMPLEMENTED / 일부 VERIFIED | `task/a01`, `task/b02`, `task/c02`; C-02 local MySQL/HTTP tests | 팀원 2 범위. C-02 API·DB 테스트는 강함, 최신 브라우저 전체 여정은 NOT_RUN |
| A-02, B-01, C-01, 공통 인증/Workflow | TEAM_OWNED | 팀원 1 소유 경로 및 기존 테스트 존재 | 이번 감사에서 변경/전수 재실행하지 않음 |
| DOC-01~13 | IMPLEMENTED | `required_document`, `document_file`, `C02IntakeService`, upload tests | 서버 저장 파일 기반 제출 검증은 VERIFIED; 모든 화면 E2E는 NOT_RUN |
| DOC-14~22 | PARTIAL | CLOVA adapter, `OcrPersistence`, `C02PowerOfAttorney*` tests | C-02 공식 위임장 경로만 준비. 실제 CLOVA 호출/운영 template evidence E2E는 NOT_RUN |
| REQ-01~19 | PARTIAL | `WorkRecordsView`, C-02 case/resume BFF | C-02 중심으로 확인. 전 업무 공통 화면 전수 E2E 미실행 |
| STAFF-01~05, 07~10, 12~14, 18, 20, 22 | IMPLEMENTED / 부분 VERIFIED | C-02 BFF, decision persistence, staff brief, `C02DocumentReview*` | 규칙/DB 테스트 통과 기록. 실제 직원 화면 E2E는 NOT_RUN |
| STAFF-06 | VERIFIED | `C02DocumentReviewService/Controller`, `C02DocumentReviewLocalMySqlIT` | 문서별 확인·보완·이력·권한·결정 정합성 테스트 |
| STAFF-11, STAFF-13 | IMPLEMENTED | Case list BFF, document review history/timeline | 목록/이력 HTTP·브라우저 전수 확인은 NOT_RUN |
| STAFF-15, STAFF-16 | TEAM_OWNED / PARTIAL | C-01 특화 요구 | C-02 감사 범위를 넘어 팀원 1 코드 검증 필요 |
| STAFF-17 | NOT_IMPLEMENTED | 재신청 Case 비교 UI/서비스를 찾지 못함 | 제안 항목; P2 |
| STAFF-19 / RAG-10 | PARTIAL | `ai-server/app/staff_agent`, `case_brief.py`, RAG facts | 자동 점검 근거 연결은 존재. 별도 직원 RAG 질의응답 화면은 명세 P1이며 찾지 못함 |
| STAFF-21, NOTI-20/21, HIST-11 | VERIFIED | `C02ClarificationService`, `C02StatementService`, controllers, `C02ClarificationLocalMySqlIT` | 질문→웹알림→답변→revision 재점검, 30일 진술 TTL 테스트. 브라우저는 NOT_RUN |
| VISIT-01~05, 07, 11~14 | IMPLEMENTED / 부분 VERIFIED | `VisitReservationService`, controllers/BFF, reservation tests | 결과통지 후 예약·권한·정원·이력 코드/DB 테스트 확인 |
| VISIT-06, VISIT-08, VISIT-10, NOTI-23 | VERIFIED | manual assignment/change/expiry/concurrency local MySQL tests; `ReservationJobs` | Fake ranking 기반. 실제 Agent/브라우저 E2E는 NOT_RUN |
| NOTI-01~06, 10~23 | IMPLEMENTED / 부분 VERIFIED | `NotificationWriter`, notifications UI/API, C-02/예약 tests | 웹 알림 중심. 모든 알림 UI 상태 E2E 미실행 |
| NOTI-07~09, NOTI-24 | OUT_OF_SCOPE | 팀 결정상 실제 SMTP 미사용 | 명세와 서면 범위 합의 필요 |
| HIST-01~09 | PARTIAL | chat/workflow/work records code | 저장·조회 경로는 존재; 전체 보관/화면 테스트 전수 미실행 |
| HIST-10 / T-13 | NOT_IMPLEMENTED | chat 7일 삭제 `@Scheduled` 구현을 찾지 못함 | P1 개인정보 보관 준수 공백 |
| T-08 | NOT_IMPLEMENTED | 72시간 미제출 draft reset job을 찾지 못함 | P1 (데이터 보관 정책) |
| T-14 | VERIFIED | `C02StatementService.deleteExpired()` + scheduled invocation; local test | C-02 case statement 30일 삭제 |
| RAG-01~08, 11 | IMPLEMENTED / CODE_ONLY | existing ai-server RAG structure/contracts | 이 감사에서 인덱스 재구축/실 검색 품질 시험은 하지 않음 |
| RAG-09 | PARTIAL | review/run facts have document IDs, versions; test coverage | 검색시각·근거버전의 모든 response persistence를 전수 입증 못함 |
| AXE-01 | PARTIAL | test fixtures cover positive/mismatch/failure/manual cases | 발표용 영구 비교 Case 세트 없음 |
| AXE-02~06, AXE-08 | NOT_IMPLEMENTED | 측정 집계 서비스/테이블/API를 찾지 못함 | P2 |
| AXE-07 | PARTIAL / VERIFIED for safety | reservation concurrency and gate tests | 자동/수동 비율 측정 대시보드는 없음 |
| MOCK-01~16 | IMPLEMENTED / TEAM_OWNED mixed | mock-bank policy provider/tests; finance fixtures | external mock-bank integration broad E2E is NOT_RUN |
| SYS-01~24 / T-01~21 | PARTIAL | V1~V14 schema and selected integration tests | 수용 테스트 전체 매핑·전수 실행은 아직 없음; Sign/SMS는 팀원 1 소유 |

### 우선 확인 기능의 증거

| 기능 ID | 요구사항 | 구현 파일/API | DB | 테스트 | 상태 |
|---|---|---|---|---|---|
| STAFF-06 | 직원 문서 확인·보완·이력 | `C02DocumentReviewService`, `C02DocumentReviewController` | `required_document`, `document_file`, `event_log` | `C02DocumentReviewLocalMySqlIT` | VERIFIED |
| STAFF-21 | 추가 사실확인·동일 Case 재점검 | `C02ClarificationService/Controller` | `clarification`, `agent_review_run`, `case_revision` | `C02ClarificationLocalMySqlIT` | VERIFIED |
| NOTI-20/21 | 질문/답변 웹알림 | `NotificationWriter`, clarification service | `notification` | same test | VERIFIED |
| HIST-11 | 최소 진술·30일 TTL | `C02StatementService/Controller` | `case_statement` | same test | VERIFIED |
| VISIT-06 | 자동 순위/수동 인계 | `VisitReservationService`, ranking port | `visit_reservation`, `event_log` | `VisitReservationManualLocalMySqlIT` | VERIFIED with Fake ranking |
| VISIT-08/10 | 수동 배정/변경 | same | same | same | VERIFIED |
| NOTI-23 | 예약 변경/만료 알림 | `ReservationJobs`, service | `notification`, reservation | same | VERIFIED |
| RAG-09/10 | 근거 이력/직원 질의 | `case_brief.py`, staff brief facts | `agent_review_run` report | focused unit tests | PARTIAL |

## 구현률

198개 ID 중 팀원 1 소유·범위외·제안 기능을 “완성”으로 포함하면 수치가 왜곡된다. 따라서 이번 감사는 다음 보수적 지표를 사용한다.

| 지표 | VERIFIED | IMPLEMENTED 이상 | PARTIAL/NOT/TEAM/OUT 제외 | 비고 |
|---|---:|---:|---:|---|
| 전체 명세서 | 15개 이상 확인 | 70개 이상 코드 경로 확인 | **정확한 수치 산출 보류** | 198개 전부의 실행근거가 없으므로 퍼센트 확정 금지 |
| T2 담당(A-01/B-02/C-02/문서·직원 Agent·알림·예약) | 8개 핵심 세로기능 | 35개 이상 관련 ID | **검증 완료율 산정 불가** | 별도 기능-ID↔테스트 매트릭스가 필요 |

## 남은 우선순위

| 우선순위 | 항목 | 영향 |
|---|---|---|
| P0 | 최신 코드의 고객→직원→예약 브라우저 E2E | 자동 테스트는 통과했지만 실제 UI/인증 조합 미확인 |
| P1 | 72시간 DRAFT reset(T-08), 채팅 7일 삭제(HIST-10/T-13) | 명세상 보관/삭제 의무 미충족 |
| P1 | 지속 가능한 API 기반 발표용 fixture seeder | 현 fixture는 테스트 단위로 reset되어 장기 브라우저 시연 데이터가 아님 |
| P1 | 직원 RAG 질문 UI(RAG-10/STAFF-19) | 자동 brief와 별도의 직원 질의 화면을 찾지 못함 |
| P2 | AXE-02~06/08 측정·집계 | 업무효율 성과를 수치로 주장할 근거 없음 |
| P2 | STAFF-17 재신청 비교 | 명세상 제안 기능 |
| P2 | SMTP 명세 충돌 | 팀의 “웹 알림만” 결정을 문서 승인으로 확정 필요 |

## 실행 증거

이번 워크트리에서 이미 보고된 격리 MySQL 검증은 C02 clarification 10, document review 10, draft/brief 16, demo flow 5, manual reservation 8 테스트다. 본 감사 중에는 공유 DB·서버·외부 공급자를 변경하지 않는다. `scripts/demo/seed-isolated.ps1`이 동일한 guarded-schema 실행 진입점이다.
