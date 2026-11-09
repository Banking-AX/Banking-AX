# Banking-AX 격리 시연 데이터 도구

이 폴더의 도구는 **공유 `banking_ax` 스키마를 절대 대상으로 하지 않는다.**
`banking_ax_demo_`로 시작하는 격리 MySQL 스키마와, 기존 통합 테스트가 명시적으로 보호하는 `banking_ax_t2_verify`만 허용한다.

## 준비

PowerShell에서 아래 환경 변수만 설정한다. 비밀번호 값은 문서·로그에 남기지 않는다.

```powershell
$env:DEMO_DB_URL = 'jdbc:mysql://127.0.0.1:13307/banking_ax_t2_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Seoul'
$env:APP_DB_USERNAME = '<test-db-user>'
$env:APP_DB_PASSWORD = '<test-db-password>'
```

대상 스키마는 빈 MySQL 8 스키마여야 한다. Flyway는 Spring 통합 테스트가 V1~V14를 적용한다.

## 실행

```powershell
.\scripts\demo\seed-isolated.ps1 -Mode DryRun
.\scripts\demo\seed-isolated.ps1 -Mode BuildAndVerify
.\scripts\demo\verify-isolated.ps1
```

`BuildAndVerify`는 테스트 fixture를 임의 SQL 상태 변경이 아닌 C-02/직원 검토/예약 서비스의 실제 통합 테스트로 만든다. 각 테스트는 다음 테스트 전에 격리 fixture를 초기화한다. 즉 **자동 검증용 재현 데이터**이며, 브라우저에서 장시간 보존하는 발표용 Case 세트는 아직 생성하지 않는다.

`fixtures/demo-scenarios.json`은 발표용으로 필요한 장기 보존 상태와 실제 생성/검증 경계를 명시한다. `PRESENTATION_READY=false`인 항목은 아직 공유 또는 장기 격리 DB에 만들지 않는다.

## 안전 장치

- `DEMO_DB_URL`에 `banking_ax_demo_` 또는 `banking_ax_t2_verify`가 없거나 정확히 `banking_ax`이면 즉시 중단한다.
- 공유 DB, Case #7~#9, 실행 중 18081/9001/5175에는 연결·변경하지 않는다.
- 실제 SMS, 이메일, CLOVA, 외부 LLM 호출을 하지 않는다. 통합 테스트는 Fake Agent/Mock OCR 범위를 명시한다.
