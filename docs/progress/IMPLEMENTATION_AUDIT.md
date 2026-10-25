# Banking-AX 구현 현황 감사 및 잔여 개발 계획

> 감사 기준: `origin/main`의 PR #9 병합 커밋 `39aee9a` (2026-10-25). 코드·계약·DDL·테스트 설정을 대조했고, 이 문서에 적힌 테스트를 새로 실행했다. T1의 별도 JPA/Flyway 브랜치는 main에 없으므로 완료로 계산하지 않는다.

## 1. 프로젝트 구현 현황 한눈에 보기

| 항목 | 판정 | 구현 매체 / 근거 |
| --- | --- | --- |
| 금융업무 6개 G1 커버리지 | **약 55% (±10%)** | A-01/B-02/C-01/C-02는 개발용 흐름 일부, A-02는 Fake 조회, B-01은 계약 수준이다. 산식은 7절. |
| 기획서 전체 서비스 완성도 | **약 31% (±8%)** | 지속성·대화/복합 workflow·실고객 인증·직원 운영·배포가 빠져 있다. |
| Spring 업무 규칙 | PARTIAL | A-01/B-02/C-01/C-02는 InMemory/Fake 기반 규칙/API, A-02는 Tool 조회, B-01 구현 없음. |
| Customer Agent | PARTIAL | 다섯 taskCode의 명시적 Registry/LangGraph 노드는 있으나 자연어 해석·복합 분해·대화 저장이 없다. |
| 직원 Agent / RAG | PARTIAL | C-01/C-02 사전점검 및 RAG citation 경계는 있으나 결과 DB 저장/직원 최종검토가 없다. |
| React | PARTIAL | 화면·일부 prepare fetch는 있으나 로그인/BFF·안전한 실제 command/submit 연결이 제한적이다. |
| DB/Flyway/JPA | NOT_IMPLEMENTED | DDL은 `docs/01_schema_mysql8.sql`에 있으나 `backend/src/main/resources/db/migration/README.md`가 V1 부재를 명시한다. |
| 운영 준비 | NOT_IMPLEMENTED | CI workflow, Docker/Compose, 실제 로그인, MySQL 통합, Outbox/복구를 찾지 못했다. |

### 판정 기준

| 상태 | 뜻 |
| --- | --- |
| VERIFIED | 코드와 실행 경로를 확인했고 이번 감사에서 관련 자동 테스트를 실행해 통과했다. 전체 외부 E2E를 뜻하지는 않는다. |
| IMPLEMENTED_UNVERIFIED | 코드/계약은 있으나 이번 감사에서 그 실행 경로를 직접 검증하지 못했다. |
| PARTIAL | 일부 단계, Fake/InMemory, API 계약 또는 UI 상태 모델에 한정됐다. |
| NOT_IMPLEMENTED | 필요한 실행 코드가 main 기준에 없다. |

## 2. 기획서 대비 요구사항 전체 목록

기준 문서: `AGENTS.md`, `docs/00_기능명세서_v1.7.1.md`, `docs/04_UI_화면목록.md`, `docs/01_schema_mysql8.sql`, `docs/contracts/**`.

| 원 기획 요구사항 | 상태 | 코드/계약 근거 |
| --- | --- | --- |
| A/B/C 유형별 약 2개, 총 6개 업무 | PARTIAL | A-01/A-02/B-02/C-01/C-02 코드 존재, B-01 실행 구현 없음. |
| 고객 자연어 요청 해석 | NOT_IMPLEMENTED | `customer_agent/router.py`는 구조화된 `taskCode` DTO만 Registry로 전달한다. |
| 복합 금융업무 요청 분리·실행 | NOT_IMPLEMENTED | `WorkflowPort`/`WorkItem` InMemory 골격은 있으나 복수 업무 분해/순서 실행기가 없다. |
| 고객 대화 기록 저장 | NOT_IMPLEMENTED | `CustomerChatShell.tsx`는 자리표시자이며 conversation/message 저장 코드가 없다. |
| 상태 저장 및 중단 후 재개 | PARTIAL | `core/workflow/InMemoryTaskHandler.java`에 start/resume/hold/complete가 있으나 메모리 한정, HTTP/DB 복원이 없다. |
| 직원 연계 상담 Case 생성 | PARTIAL | C-01/C-02가 `InMemoryCasePort`를 사용하나 영속 WorkItem 연결/운영 API가 없다. |
| 증빙서류 제출·누락 확인 | PARTIAL | C 업무에 Fake/스냅샷 필수서류 판단은 있으나 `DocumentPort`는 계약/Fake, 업로드/저장/진위가 없다. |
| 직원 Agent 요청·금융·서류 요약 | PARTIAL | `core/staff_engine` C-01/C-02 check가 `reviewItems`를 만들지만 DB에서 수집/저장하지 않는다. |
| 사전검토 및 결과 기록 | PARTIAL | LangGraph/HTTP router는 있으나 `agent_review_run`/`review_item` 저장·최종결정 반영이 없다. |
| 가능/미충족/서류부족 후속 처리 | PARTIAL | 업무별 Spring 응답과 review 상태는 일부 분기. 추가서류 재제출·재검토·예약·알림은 없다. |
| 웹·이메일 알림 | NOT_IMPLEMENTED | `docs/contracts/notification/`와 Fake만 있고 web/email/retry/outbox가 없다. |
| 모의 금융 데이터 PoC | VERIFIED | A-01/B-02/A-02/C-01/C-02 Fake/InMemory fixture와 테스트가 있다. MySQL Mock 데이터셋은 없다. |

### 원 기획과 최신 명세의 차이

* 최신 v1.7.1을 기준으로 했다. `AGENTS.md`는 과거 v1.2/v1.6의 **C 제출과 동시 예약**을 폐기하고, 직원 검토 뒤 예약하는 v1.7.1 흐름을 명시한다. 따라서 사전 예약 미구현은 결함으로 계산하지 않았다.
* 원 기획의 자연어/복합 요청은 최신 실행계획에도 남아 있지만, main의 구조화된 API에는 구현되지 않았다.
* DDL의 32개 테이블과 main의 계약/Fake 구현은 별개다. Flyway/JPA가 없으므로 DB 구현으로 계산하지 않았다.

## 3. 6개 금융업무별 상세 구현 현황

| 업무 | Spring 업무 규칙·API | Customer Agent / HTTP | React | 영속화·인증·테스트 | 판정 |
| --- | --- | --- | --- | --- | --- |
| **A-01 거래내역 조회** `TRANSACTION_SEARCH` | `task/a01/TransactionSearchService`, `FakeTransactionQueryPort`; 소유권/기간/입출금/정렬/page. `/api/v1/tools/transaction-search`. | `tasks/transaction_search.py`, Registry, `SpringHttpToolGateway` HTTP 코드. | `/tasks/a01/:workItemId`, FastAPI execute 호출; 고정 `customerId:1`, 로그인 없음. | Fake 계좌/거래. Maven `TransactionSearchServiceTest` 3건, Python Registry test 통과. 서버간 A-01 E2E는 이번 감사 미실행. | PARTIAL — 개발 조회 경로 VERIFIED, DB/principal/E2E 미확인 |
| **A-02 대출계좌 정보 조회** `LOAN_INQUIRY` | `task/a02/FakeLoanQueryPort`, `/api/v1/tools/loan-inquiry`. | `tasks/loan_inquiry.py`, Registry/Fake round-trip. | 전용 화면 없음. | Fake only; Python A-02 테스트 통과. | PARTIAL |
| **B-01 채무변제 충당순서 변경** `REPAYMENT_ORDER_CHANGE` | Service/Controller/Adapter 없음. | 노드/Registry 없음. | 화면 없음. | `docs/contracts/b01/phase0-b01.md`와 fixture만 존재. | NOT_IMPLEMENTED (계약 수준) |
| **B-02 청년 주택드림 청약통장 Mock 해지** `HOUSING_SAVINGS_CLOSE` | `B02Controller`, `B02Service`, `B02MockCloseCommand`; prepare→OTP→verify→execute, B2-R1~R6, version/owner/requestId/binding. | `tasks/housing_savings_close.py`, Gateway. prepare HTTP 코드와 명시 동의/서명/binding 뒤 execute guard. | `/tasks/b02/:workItemId`; 안전한 BFF 부재로 API 실행은 의도적으로 연결하지 않은 UI 상태 모델. | InMemory/Fake OTP/Mock close. `B02ServiceTest` 4 + embedded HTTP E2E 1 통과. 실제 principal/DB/outbox 없음, OTP 소비-명령 실패 원자성 없음. | PARTIAL — 개발 Command/HTTP VERIFIED, Agent execute 전구간·React E2E 미검증 |
| **C-01 채무조정 접수** `DEBT_ADJUSTMENT` | `C01Controller`, `C01IntakeService`: start/intake/consent/OTP/submit. | `tasks/debt_adjustment.py`, Registry, C-01 staff check. | `/tasks/c01`, UI 상태 모델만. | InMemory Case/doc slot/OTP. Maven 보안 3 + service 6, Python C-01 2 통과. 실제 파일/DB/principal/staff 결과 저장 없음. | PARTIAL |
| **C-02 상속예금 접수** `INHERITANCE_DEPOSIT` | `C02Controller`, `C02IntakeService`, `StaffAgentHttpClient`; 범위/서류/동의/OTP/submit 경계. | `tasks/inheritance_deposit.py`, staff check, `/api/v1/staff-agent/check`. | `/tasks/c02/:workItemId`; FastAPI prepare만 연결, submit/OTP/파일은 미연결. | InMemory Case/Fake DocumentStore. Maven C-02 service 6, Python C-02/Staff router 통과. 외부 FastAPI 의존 HTTP E2E 3건 skip. | PARTIAL — Graph VERIFIED, Spring↔FastAPI submit E2E/DB 미검증 |

## 4. 공통 기능 구현 현황

| 기능 | 상태 | 근거 | 남은 핵심 |
| --- | --- | --- | --- |
| Customer Agent Router/Registry | PARTIAL | `router.py`, `registry.py`, `tool_gateway.py`; A-01/A-02/B-02/C-01/C-02 명시 등록 | B-01, 자연어/복합 분해, durable request state |
| Workflow/WorkItem | PARTIAL | `InMemoryTaskHandler` 단일 활성 workflow, resume/hold/complete | Controller, DB, 재기동 복원, 복합 실행 |
| CasePort | PARTIAL | `InMemoryCasePort`가 C-01/C-02 생성/revision/submit/check result | JPA adapter, WorkItem FK, transactional revision/audit |
| MySQL/JPA/Flyway | NOT_IMPLEMENTED | `application.yml`/DDL만 존재, migration README의 V1 없음 | T1 core persistence |
| Verification/OTP | PARTIAL | Caffeine OTP/binding, Fake sender, scope/one-time consume | principal/SMS, durable replay prevention, transaction |
| 인증·소유권 | PARTIAL | internal Gateway + actor/customer compare, Controller security test | Gateway는 customer login이 아님; BFF/RBAC/session 필요 |
| 멱등성·상태전이 | PARTIAL | B-02/C requestId/revision tests | DB unique/outbox/retry/multi-instance 보장 |
| Conversation/재개 | NOT_IMPLEMENTED | chat placeholder + InMemory workflow만 | 대화/Task snapshot persistence |
| Outbox·복구 | NOT_IMPLEMENTED | 코드 없음 | event/outbox/retry/dead letter |

## 5. 직원 Agent·서류·알림 구현 현황

| 항목 | 상태 | 구현 매체 / 한계 |
| --- | --- | --- |
| `StaffCheckExecutor` / `CheckRegistry` | VERIFIED | C-01/C-02 명시 등록, `CompiledStateGraph`, stale/error 중단, `reviewItems`; Python test 통과. |
| 기존 RAG | VERIFIED | Chroma + BM25/n-gram + RRF, citation(문서/제목/절/페이지/verified) 보존, unverified 자동 통과 금지. API tests는 mock backend 중심이며 live index 품질은 미검증. |
| Case·서류 조회 | PARTIAL | snapshot/Fake DocumentStore 조립. `required_document`/`document_file` DB, 업로드, OCR, 진위 미구현. |
| 사전검토 결과 저장 | PARTIAL | staff HTTP response는 있으나 review run/item DB 저장 및 Spring 최신 revision 반영 없음. |
| 직원 최종결정·추가서류 요청 | NOT_IMPLEMENTED | DDL/계약만. |
| 직원 화면/RBAC | NOT_IMPLEMENTED | `StaffShell.tsx` placeholder. |
| NotificationPort | PARTIAL | 계약과 Fake event 기록만; web/email/retry/outbox 없음. |
| VisitPort/예약 | PARTIAL | 계약과 Fake slot만; branch/staff/concurrency API/UI 없음. |
| OCR | NOT_IMPLEMENTED | G1 범위에서도 제외. |

## 6. 실제 HTTP/E2E 검증 현황

### 이번 감사에서 실행한 검증

| 명령 / 범위 | 결과 | 비고 |
| --- | --- | --- |
| `backend`: `C:\Temp\apache-maven-3.9.9\bin\mvn.cmd test` | **PASS** — 30 run, 0 fail, 0 error, 3 skip | A-01 3, B-02 service 4 + embedded HTTP E2E 1, C-01 9, C-02 service 6, contracts 4. C-02 external FastAPI E2E 3건 skip. |
| `ai-server`: `.venv\Scripts\python.exe -m pip check` | **PASS** | broken requirements 없음. |
| `ai-server`: `python -m unittest tests.customer_agent.test_customer_agent tests.test_staff_phase0 tests.test_rag_api tests.test_c01_g1 tests.test_c02_integration -v` | **PASS — 25/25** | Registry, B-02 execute guard, C-01/C-02 staff graph, RAG contract, Fake boundary 확인. |
| `frontend`: `npm ci` | **PASS** | lockfile 기준 47 packages, audit 0 vulnerability. |
| `frontend`: `npm run build` | **PASS** | `tsc -b && vite build` production bundle 생성. |

| HTTP 흐름 | 감사 기준 판정 |
| --- | --- |
| Spring B-02 prepare/OTP/verify/execute | VERIFIED — `B02HttpE2eTest` embedded Spring HTTP, 단일 JVM InMemory/Fake OTP/Mock command 범위 |
| FastAPI Agent → Spring B-02 prepare | IMPLEMENTED_UNVERIFIED — 코드와 이전 G1 기록은 있으나 이번 감사에서 두 서버는 기동하지 않음 |
| FastAPI Agent → Spring A-01 | IMPLEMENTED_UNVERIFIED — Gateway 코드는 있으나 이번 감사의 process-to-process 호출 없음 |
| Spring C-02 submit → FastAPI staff agent | NOT VERIFIED — `C02HttpE2eTest` 3건이 external FastAPI 부재로 skip |
| staff Agent → live Chroma | NOT VERIFIED — tests는 fixture/mock 경계 |
| React → secure BFF → Spring | NOT IMPLEMENTED — 일부 direct FastAPI fetch 외 principal/BFF 없음 |
| 모든 업무 전체 E2E | NOT IMPLEMENTED |

## 7. 전체 진행률 추정과 산정 근거

수치는 코드 커버리지·운영 준비율이 아니라 main의 개발용 PoC 진척 추정이다. DB, principal, 다중 인스턴스, 외부 전송이 빠진 기능은 완료로 가산하지 않았다.

### A. 금융업무 개발용 G1: **약 55% (3.30 / 6.00)**

점수: 1.00=규칙/API+Agent+안전 UI/API+E2E, 0.75=개발 수직 흐름이나 InMemory/UI gap, 0.50=규칙/API+Agent/Fake 일부, 0.25=계약/fixture, 0=없음.

| 업무 | 점수 | 근거 |
| --- | ---: | --- |
| A-01 | 0.70 | Spring/Agent/React 조회 경로와 단위/Registry test, Fake·로그인 부재·이번 감사 E2E 미실행 |
| A-02 | 0.45 | Fake 조회 API/Agent test, UI/E2E 부재 |
| B-01 | 0.10 | 계약/fixture만 |
| B-02 | 0.75 | Mock Command/embedded HTTP E2E/Agent/UI, browser API와 FastAPI execute 전구간 미검증 |
| C-01 | 0.60 | lifecycle/보안/OTP/Agent test, UI 연결·문서·persisted staff flow 없음 |
| C-02 | 0.70 | Spring/Agent/staff HTTP boundary/React prepare, external staff E2E skip·InMemory 문서 |

### B. 기획서 전체 서비스: **약 31% (31.3 / 100)**

| 가중 영역 | 가중치 | 현재 수준 | 환산 | 산정 이유 |
| --- | ---: | ---: | ---: | --- |
| 6개 업무 수직 흐름 | 30 | 55% | 16.5 | 위 G1 산식 |
| Customer Agent·자연어·Workflow·대화 | 15 | 30% | 4.5 | Registry/구조화 실행만 |
| 영속성·정합성·복구 | 20 | 5% | 1.0 | DDL/설정 외 Flyway/JPA/outbox 없음 |
| 직원·서류·후속 처리 | 15 | 25% | 3.8 | 사전점검/RAG/Fake만 |
| React 실제 연동·인증/RBAC | 10 | 20% | 2.0 | UI/일부 fetch만 |
| 테스트·배포·운영성 | 10 | 35% | 3.5 | unit/contract test는 있으나 MySQL/CI/Docker/monitoring 없음 |
| **합계** | **100** |  | **31.3** | **약 31% (±8%)** |

## 8. T1 남은 작업

| 기능 | 우선순위 | 의존성 | 예상 변경 파일/영역 | 완료 조건·테스트 | 충돌 |
| --- | --- | --- | --- | --- | --- |
| Flyway V1 + JPA core persistence | P0 | 빈 개발 DB/DDL 확정 | `db/migration`, core entity/repository/adapter | Testcontainers/DB migration, Workflow/Case/WorkItem restart persistence, revision/ownership IT | 매우 높음 |
| customer/employee principal·RBAC/BFF | P0 | identity 방침/JPA 모델 | backend auth/BFF, frontend session | role/타인 접근 통합 test, browser token 비노출 | 높음 |
| Workflow/WorkItem REST·conversation/resume | P0 | persistence + auth | workflow/controller, conversation storage, agent adapter | 재기동 resume, one active workitem, task snapshot test | 높음 |
| B-01 수직 구현 | P1 | workflow/principal 및 정책 확정 | `task/b01`, customer task, UI/contract | 규칙/API/ownership/idempotency/HTTP test | 중간 |
| A-02 실제 UI/BFF | P1 | principal/BFF | A-02 page/API adapter | 소유권 포함 E2E | 낮음 |

## 9. T2 남은 작업

### T1 작업 전 독립 진행 가능

| 기능 | 우선순위 | 예상 영역 | 완료 조건·테스트 | 충돌 |
| --- | --- | --- | --- | --- |
| RAG evaluation/fixture 확충 | P1 | `ai-server/tests`, RAG manifest | verified/unverified/citation/empty/timeout regression | 낮음 |
| staff check contract/fixture 확충 | P1 | C-01/C-02 check tests, staff contract | stale/error/manual-review/reviewItems schema | 낮음 |
| Document/Notification/Visit Fake 계약 정리 | P2 | contracts/Fake ports | timeout/idempotency fixture와 실제 범위 정합 | 낮음 |
| React 컴포넌트/접근성 test | P2 | A-01/B-02/C-02 pages | UI가 success를 오인시키지 않는 test | 낮음 |

### T1 JPA/인증 병합 뒤 진행

| 기능 | 우선순위 | 의존성 | 완료 조건·테스트 | 충돌 |
| --- | --- | --- | --- | --- |
| DocumentPort 실제화와 C-02 서류 lifecycle | P0 | Case/file metadata JPA, auth | required/document_file 저장·소유권·누락/재제출 IT | 높음 |
| staff review run/item 저장·결과 반영 | P0 | transactional Case revision, staff auth | 최신 revision만 저장, stale 거부, AI/직원결정 분리 | 높음 |
| C-02 staff HTTP 전체 E2E | P0 | persistence/auth | Spring 최신 snapshot→FastAPI graph→revision 재검증/DB test | 중간 |
| 알림/추가서류/방문 후속 흐름 | P1 | final staff result, outbox, RBAC | dedup/retry/outbox, 재검토·예약 조건 test | 높음 |
| A-01/B-02 production-style adapter | P1 | JPA account/transaction/BFF | Fake 대체 조회/command transaction/browser E2E | 중간 |

## 10. 선행 의존성 및 추천 개발 순서

```text
Flyway/JPA core + principal/RBAC
  ├─ persisted Workflow/WorkItem + conversation/resume → complex-request execution
  ├─ persisted Case/Document → staff review persistence → final decision/additional documents
  │                                           └───────→ notifications/visit/staff UI
  └─ secure BFF/API boundary → React real API integration

병렬(T2, 현재 가능): RAG 평가, staff-check fixture/계약, Fake-port 회귀, UI component test.
```

## 11. 다음 5개 PR 제안

| 순서 | PR | 담당 | 의존성 | 완료 기준 |
| ---: | --- | --- | --- | --- |
| 1 | `core/persistence-v1`: Flyway V1 + Workflow/WorkItem/ServiceCase 최소 JPA adapter | T1 | 개발 MySQL/Testcontainers | clean migration, restart persistence, revision/ownership IT; InMemory는 test profile로 유지 |
| 2 | `core/auth-boundary`: customer/employee principal, RBAC, BFF/API 경계 | T1 | PR 1 또는 확정 identity adapter | Gateway와 customer principal 분리, 타인/role 교차 차단, browser secret 없음 |
| 3 | `workflow-conversation-resume`: 대화/task input/WorkItem 저장과 재개 | T1 | PR 1, 2 | restart resume, one-active-workitem, conversation 조회, 복합 요청 contract |
| 4 | `c02/document-and-staff-review-persistence` | T2 | PR 1, 2 | 문서 metadata/필수서류 + staff graph 결과 저장, C-02 external HTTP E2E unskip |
| 5 | `staff-outcome-notification-visit` | T2 (T1 auth 협업) | PR 2, 4 | final outcome→추가서류/알림/예약 조건, dedup/retry test; email/SMS는 fake 가능 |

`B-01`은 PR 1/2와 병렬 또는 PR 3 뒤 T1이 수직 구현한다. 그 이전 독립 InMemory 구현은 재이식 위험이 크다.

## 12. 시연 가능한 현재 범위와 아직 불가능한 범위

### 개발용으로 시연 가능

* 구조화된 taskCode로 A-01 거래내역, A-02 대출 조회, B-02 Mock 해지 사전검증, C-01/C-02 접수 초안을 Customer Agent에 전달.
* Spring 내부 Gateway에서 A-01 조회와 B-02 prepare/OTP/verify/Mock command 규칙. B-02 embedded HTTP E2E는 ACTIVE→CLOSED를 확인했다.
* C-01/C-02 staff LangGraph와 RAG citation/reviewItems contract를 Fixture로 시연.
* A-01/B-02/C-01/C-02 React 입력·안내 상태. 실제 로그인 고객의 완료 금융처리는 아니다.

### 완료로 주장할 수 없는 범위

* 자연어 복합 요청 자동 분리, 대화/WorkItem 저장과 중단 후 재개.
* MySQL 기반 customer/account/Case/document/review의 재기동·다중 인스턴스 정합성.
* 실제 로그인/RBAC/BFF와 browser-to-server 보안 경계.
* C-02 파일 업로드/OCR/진위, 추가서류 재제출, Spring→FastAPI staff Agent 전체 E2E, 직원 최종결정 저장.
* 웹/email 알림, 방문 예약, Outbox/retry/장애 복구, 실제 은행/SMS/email/운영 배포.

## 부록: 감사 근거 파일

* 명세/계약: `AGENTS.md`, `docs/00_기능명세서_v1.7.1.md`, `docs/01_schema_mysql8.sql`, `docs/03_전체컬럼_정의서.md`, `docs/04_UI_화면목록.md`, `docs/contracts/**`.
* Backend: `backend/src/main/java/com/bankingax/core/**`, `backend/src/main/java/com/bankingax/task/{a01,a02,b02,c01,c02}/**`, `backend/src/test/java/**`.
* AI: `ai-server/app/main.py`, `ai-server/app/customer_agent/**`, `ai-server/app/core/staff_engine/**`, `ai-server/app/staff_agent/**`, `ai-server/app/rag/**`, `ai-server/tests/**`.
* Frontend: `frontend/src/App.tsx`, `frontend/src/features/**`, `frontend/src/api/client.ts`.
* 실행 결과: Maven **30 run / 27 pass / 3 skip**, Python **25 pass**, `pip check` pass, `npm ci` pass, `npm run build` pass. C-02 external FastAPI opt-in HTTP E2E는 skip이다.
