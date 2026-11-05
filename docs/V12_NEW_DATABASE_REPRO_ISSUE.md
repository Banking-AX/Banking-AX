# V12 신규 DB 재현 차단 이슈

`V12__b01_repayment_order_document_type.sql`의 document_type `name` 문자열이
닫히지 않아 새 MySQL DB에서 V12 적용이 문법 오류로 중단된다. 기존 `banking_ax`
DB의 V12 history는 성공 상태이므로 이 파일을 수정하거나 Flyway repair를 실행하지
않는다. 이 문제는 Flyway 소유자가 공식 원문과 적용 이력을 확인해 별도 계약으로
해결해야 한다.

공유 DB의 V1~V13 스키마를 이용한 C-02/시드 검증은 가능하지만, 신규 DB 완전 재현
검증은 V12 정본이 복구되기 전까지 보류한다.
