# 10–15분 Banking-AX 시연 런북

## 사전 조건

1. 최신 Spring/FastAPI/React가 **격리 DB**를 바라보는 별도 포트에서 실행되어야 한다.
2. `scripts/demo/seed-isolated.ps1 -Mode BuildAndVerify`가 PASS여야 한다.
3. 지속 브라우저 fixture가 아직 없으므로, 아래의 고객/직원 클릭 시연은 **NOT_RUN / 준비 필요**다. 테스트 결과를 브라우저 E2E라고 표현하지 않는다.

## 시연 서버(18081) 체크리스트

- [ ] **A-02 대출계좌 상세 조회는 현재 시연 서버에서 지원하지 않는다.** origin/main의 PR #40·#41 등 18개 커밋이 18081 빌드에 포함되지 않았으므로 시연하지 않고, 지원한다고 말하지 않는다.
- [ ] 같은 이유로 main의 채팅 답변·대출 조회 재연결 변경도 18081에 없다. main 병합 후 재배포 전까지 유지되는 제한이다.
- [ ] Docker를 다시 켰다면 `docker start banking-ax-mysql`을 먼저 실행한다.
- [ ] 예약은 방문 24시간 이후 시간으로 고른다. 24시간 이내는 변경·취소 장면을 보여 줄 수 없다.
- [ ] 새 C-02 접수는 등록 번호로 실제 SMS가 발송된다.

## 권장 순서

| 시간 | 역할 | 화면/행동 | 기대 결과 | 대체 증거 |
|---:|---|---|---|---|
| 0:00 | 직원 | `/staff/c02/cases`에서 DEMO-01 | 제출 수와 직원 확인 필요가 분리됨 | staff overview API/test |
| 1:30 | 직원 | 원본 파일·OCR/수동확인·brief 보기 | MOCK OCR과 수동 확인을 혼동하지 않음 | document review/brief test |
| 2:30 | 직원 | DEMO-02 질문 열기 | 질문→고객 웹 알림 | clarification test |
| 3:30 | 고객 | `/notifications`, 해당 Case 답변 저장 | 답변, revision 증가, 재점검 | clarification test |
| 5:00 | 직원 | recheck 후 brief와 안내 초안 확인 | Fake LLM fixture 표시, 최종 판정 자동 아님 | brief test |
| 6:00 | 직원 | DEMO-03 문서에 보완 필요 기록 | 이력·사유 저장 | STAFF-06 test |
| 7:00 | 직원 | DEMO-01 진행 가능 최종결정 | 결과 웹 알림, 예약 가능 | decision/demo-flow test |
| 8:00 | 고객 | 예약 화면에서 지점/시간 선택 | 자동 배정 또는 대기 상태 | visit test |
| 9:00 | 직원 | DEMO-07 수동 배정 | 같은 지점/권한 직원만 확정 | manual assignment test |
| 10:00 | 고객 | DEMO-08 변경 시도 | 성공은 새 예약, 실패는 이전 예약 유지 | change test |
| 11:00 | 직원 | `/staff/policy-changes` | 교육용 v2 승인/v3 대기 구분 | policy test |
| 12:00 | 직원 | 업무함/권한 없는 계정 | 분리된 상태와 403 | access tests |

## 실제 브라우저 준비 후 클릭 검증

1. 테스트 고객으로 로그인한다. 업무 요청 기록에서 해당 C-02 Case만 연다.
2. 직원 질문 알림을 열고 답변을 저장한다. 직원 계정으로 전환해 재점검과 brief를 확인한다.
3. 직원은 문서 확인 결과를 저장한 뒤 최종결정을 직접 선택한다. 자동 승인 문구가 없어야 한다.
4. 고객은 결과 알림을 연 뒤에만 예약 화면으로 이동한다.
5. 자동 배정 실패 Case는 직원 업무함에서 수동 배정한다. 변경 실패 시 기존 예약 시간/직원이 그대로인지 확인한다.
6. 권한 없는 직원 계정으로 Case detail URL을 열어 403/접근 거부를 확인한다.

## 시연 후

고객 답변·검토·예약은 상태를 변경한다. 현재 자동 도구는 테스트마다 reset하는 검증 fixture이며, 발표 전용 영구 fixture가 승인·구현되기 전에는 시연 뒤 `BuildAndVerify`를 다시 실행해 기준 상태를 재현한다.
