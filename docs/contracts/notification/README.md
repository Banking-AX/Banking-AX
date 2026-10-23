# NotificationPort v1 — T2 Phase 0

## 호출과 DTO

Spring이 B/C 결과, C 추가 확인, 예약 상태에 대한 웹 알림을 생성하고 이메일 발송을 관리한다. A 조회에는 이메일을 만들지 않는다. 내부 함수는 다음 형태다.

```json
{"eventId":"evt-c02-result-200-1", "customerId":10, "caseId":200,
 "workItemId":null, "taskResultId":null, "type":"CASE_RESULT",
 "title":"검토 결과 안내", "content":"법적 통지 아님", "dedupKey":"case:200:result:1",
 "emailStatus":"PENDING"}
```

호출자는 Spring 내부 서비스뿐이다. `dedupKey`는 DDL `notification.uk_notification_dedup`에 대응하며 동일 이벤트의 중복 웹 알림·이메일 생성을 막는다. `emailStatus`는 `NOT_REQUIRED`, `PENDING`, `SENDING`, `SENT`, `FAILED`만 사용한다.

## 오류·재시도·Fake

`DUPLICATE_EVENT`는 기존 이벤트를 반환하는 멱등 성공, `EMAIL_DELIVERY_FAILED`와 `TIMEOUT`은 실패 상태와 재시도 정보를 남긴다. Phase 0 Fake는 메모리에 이벤트만 기록하며 SMTP/외부 이메일을 호출하지 않는다.

## 테스트

- 동일 `dedupKey`는 하나의 이벤트만 남김(T-12)
- C 결과 통지 이벤트는 예약 가능 여부의 Spring 재검증 전제가 될 수 있으나 예약을 생성하지 않음
- 실제 SMTP 호출 0회
