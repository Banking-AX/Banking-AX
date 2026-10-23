# DocumentPort v1 — T2 Phase 0

## 책임과 호출

Spring의 문서 서비스가 서류 슬롯과 파일 메타데이터의 정본이다. 고객 업로드/직원 Agent는 이 Port만 사용하며 파일 본문을 일반 RAG에 적재하지 않는다.

`POST /internal/documents`와 `GET /internal/documents/{requiredDocumentId}`는 T1 Spring 구현의 연결 대상이다. 호출자는 `SERVICE_AGENT` 또는 소유권이 검증된 고객이며, Spring이 WorkItem/Case 접근권한을 재검증한다.

## DTO

```json
{"requiredDocumentId": 501, "documentFileId": 9001, "version": 2,
 "mimeType": "application/pdf", "sizeBytes": 12345, "status": "UPLOADED"}
```

`requiredDocumentId`, `documentFileId`, `version`, `mimeType`, `sizeBytes`, `status`는 필수다. `status`는 DDL의 `NOT_SUBMITTED`, `UPLOADED`, `VALIDATION_FAILED`, `SYSTEM_VALIDATED`, `HUMAN_VERIFIED`, `NEEDS_SUPPLEMENT`만 사용한다. 파일은 10MB 이하여야 한다. 같은 슬롯의 새 파일은 다음 version으로 등록하며 최신 파일은 하나다.

## OCR 경계·오류·Fake

OCR 호출 권한은 `document_type.source_kind=SHINHAN_OFFICIAL`, `source_verified=true`, `ocr_enabled=true`일 때만 있다. 그 외에는 `method=NONE`, `status=NOT_APPLICABLE`로 기록하고 직원 확인으로 넘긴다. 이 Port는 OCR 본문 추출을 수행하지 않는다.

오류: `DOCUMENT_NOT_FOUND`, `DOCUMENT_VERSION_CONFLICT`, `FILE_TOO_LARGE`, `UNSUPPORTED_MIME`, `FORBIDDEN`, `TIMEOUT`.

Phase 0 Fake는 메모리 슬롯별 version과 메타데이터만 보관한다. 예: 위 DTO를 등록한 뒤 동일 슬롯 조회 시 같은 version을 반환한다. 실제 저장소/OCR은 호출하지 않는다.

## 테스트

- 같은 슬롯의 version 증가와 최신 파일 조회
- 10MB 초과 및 허용하지 않은 MIME 거절
- 일반 증명서에 OCR 호출이 발생하지 않음(T-09)
