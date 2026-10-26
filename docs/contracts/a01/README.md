# A-01 거래내역 조회 Tool 계약 — G1

`POST /api/v1/tools/transaction-search`는 내부 Customer Agent→Spring 경계다. 호출자는 `X-Tool-Gateway-Token`, `X-Actor-Id`, `X-Actor-Role: CUSTOMER`를 포함한다. Spring은 actor/customerId와 계좌 customerId를 재검증한다.

요청 DTO: `requestId`, `workItemId`, `customerId`, `accountId`, `startDate`, `endDate`, 선택 `transactionType(IN|OUT)`, `minimumAmount`, `maximumAmount`, `keyword`(상대방/적요 부분일치), `sortOrder(RECENT|OLDEST)`, `page`, `size`.

응답은 표준 `ApiEnvelope`의 `data`에 마스킹 계좌번호, `Asia/Seoul` 기준 거래시각, `BigDecimal` 금액, 안정 정렬(`tx_at DESC, id DESC`), 페이지 메타데이터를 담는다. 날짜 범위 역전은 `INVALID_DATE_RANGE`, 존재하지 않는 계좌는 `NOT_FOUND`, 타 고객 계좌/actor 불일치는 `FORBIDDEN`이다.

기본 G1은 `FakeTransactionQueryPort`를 사용한다. `APP_A01_TRANSACTION_QUERY_PORT=jdbc`이면 `JdbcTransactionQueryPort`가 DDL의 `deposit_account`/`account_transaction`에서 바인딩 파라미터 기반으로 필터·정렬·페이징한다. 이 모드는 해당 Mock 금융 테이블이 사전에 배포된 MySQL에서만 사용한다. A 조회는 직원 Agent, OCR, 이메일, VisitPort를 호출하지 않는다. 자연어 조건 추출은 현재 계약 범위 밖이며, Agent에는 구조화된 조건만 전달한다.
