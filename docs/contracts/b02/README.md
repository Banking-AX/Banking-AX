# B-02 청년 주택드림 청약통장 Mock 해지 계약

## 범위와 책임

- 업무 코드: `HOUSING_SAVINGS_CLOSE`
- B-02는 실제 영업점 업무를 비대면으로 가정한 **개발용 Mock 시나리오**다. 실제 은행 계좌 해지, 이체, 지급, 이자·세금 계산을 수행하지 않는다.
- Spring Java가 B2-R1~R6 판정, 고객 소유권, 동의·모의서명·OTP binding, 버전, 상태 및 Command 실행을 최종 재검증한다.
- Customer Agent는 명시적으로 등록된 Tool만 호출하며 Spring 응답을 전달한다. 금융 규칙이나 성공 결과를 생성하지 않는다.
- 모의서명은 법적 전자서명이 아니며, Gateway Token은 실제 로그인 고객 principal을 대체하지 않는다.

## HTTP 경계

모든 API는 내부 Gateway 헤더 `X-Tool-Gateway-Token`, `X-Actor-Id`, `X-Actor-Role: CUSTOMER`를 요구한다. `actorId != customer` 또는 계좌 소유자가 다르면 `FORBIDDEN`으로 거부한다.

| 단계 | API | 요청 핵심 필드 | 성공 응답 `data` |
| --- | --- | --- | --- |
| 사전판정 | `POST /api/v1/b02/prepare` | `requestId`, `customer`, `account`, `waive`, `taxed` | `rate`, `rule`, `manual` |
| OTP 발급 | `POST /api/v1/b02/{accountId}/otp` | `requestId`, `customer`, `version`, `consent`, `signed`, `phone` | `challengeId`, 마스킹된 전화번호, 만료시각 |
| OTP 검증 | `POST /api/v1/b02/otp/verify` | `requestId`, `customer`, `challenge`, `otp` | `bindingId`, 고객·계좌·액션·version bound binding |
| Mock 실행 | `POST /api/v1/b02/{accountId}/execute` | `requestId`, `customer`, `version`, `waive`, `taxed`, `binding` | `status=CLOSED`, 적용 `rate`, `rule` |

응답은 기존 `ApiEnvelope`를 사용한다. 성공 시 `requestId`와 `taskCode=HOUSING_SAVINGS_CLOSE`를 보존하며, 오류 시 `error.code`를 반환한다. FastAPI Tool Gateway는 `requestId`를 prepare와 execute 요청 모두에 전달한다.

## B2-R1~R6 구현 수준

| 규칙 | Spring 판정 |
| --- | --- |
| B2-R1 | 가입 1개월 이내면 `NO_INTEREST` |
| B2-R2 | 2년 미만·당첨 해지 아님이면 `BASE_RATE` |
| B2-R3 | 고객의 우대 포기 선택이면 `BASE_RATE`; HTTP E2E 검증 완료 |
| B2-R4 | 2년 이상 또는 당첨 해지이며 과세사실 없음이면 `PREFERRED_RATE` |
| B2-R5 | 과세사실 있음이면 `MANUAL_REVIEW_REQUIRED`; 자동 실행 차단, 영업점 상담 안내 |
| B2-R6 | 과세증명서 미확인이면 `DOCUMENT_REQUIRED`/`REQUIRED_DOCUMENT_MISSING`; 우대 포기(B2-R3) 외에는 자동 실행 차단 |

## 실행 보호 및 상태

1. OTP 발급은 `consent=true`, `signed=true`, `ACTIVE` 상태 및 요청 `version` 일치가 선행되어야 한다.
2. 검증된 `B02_EXECUTE` binding은 계좌·고객·version에 bound되고 execute 시 한 번만 소비된다.
3. execute는 `ACTIVE`/version/소유권을 다시 확인하고 B2-R5/B2-R6을 다시 판정한다.
4. 같은 `requestId`와 동일 입력은 저장된 결과를 반환한다. 같은 `requestId`에 다른 입력은 `DUPLICATE_REQUEST_CONFLICT`다.
5. 다른 `requestId`로 이미 `CLOSED` 계좌를 실행하면 `STATE_CHANGED`다.
6. `B02MockCloseCommand`는 개발용 Mock Command 경계다. 기본 구현은 외부 은행 호출을 하지 않으며 상태는 `B02Service`의 InMemory fixture에서 `ACTIVE → CLOSED`로 변경된다.

## 검증 결과

- Spring Boot 랜덤 포트 HTTP E2E에서 prepare → OTP 발급 → OTP 검증 → execute → `CLOSED`를 확인했다.
- CapturingOtpSender는 `B02HttpE2eTest`에만 주입되며 OTP 평문을 HTTP 응답, 로그 또는 브라우저에 노출하지 않는다.
- B2-R3, B2-R5, B2-R6, Gateway Token 오류, 타 고객 접근, stale version, binding 재사용, requestId 멱등성·충돌, CLOSED 재실행 및 단일 JVM 동시 execute를 검증했다.
- Command 실패 주입 시 OTP는 이미 소비되고 계좌는 `ACTIVE`로 남으며 재사용 binding은 거부된다. 이 동작은 검증됐지만 원자적 보장은 아니다.

## React 및 후속 경계

`HousingSavingsClosePage`는 B2-R3/R5/R6 안내와 동의·모의서명 UI만 제공한다. 브라우저에 내부 Gateway Token을 노출하지 않으므로 현재 React는 prepare/OTP/execute HTTP를 직접 호출하지 않으며, 해지 완료를 표시하지 않는다.

다음은 미구현이다.

- 실제 로그인 principal, SOLAPI 실제 발송, 실제 은행 Command, 이메일 알림
- JPA/MySQL 영속화, `task_result`/동의·이력 저장, Outbox 및 재시도
- OTP 소비와 Command 상태 변경의 DB 트랜잭션 원자성
- 다중 인스턴스 락·멱등성 보장
- FastAPI → Spring execute 전체 E2E와 React → 서버 인증 경계
