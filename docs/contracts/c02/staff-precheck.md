# C-02 직원 사전점검 연동 v1 — T2 Phase 0

`INHERITANCE_DEPOSIT`의 `STAFF_PRECHECK`은 제출 완료된 Case만 받는다. C-02의 방문 지점·시간은 입력으로 받지 않으며, 직원 최종 결과·웹 통지 후 VisitPort로 이어진다.

입력의 상속인별 `visitStatus`에서 `UNCONFIRMED_ABSENT`는 잠정 표시일 뿐 불참·위임 적격성 확정값이 아니다. 일반 가족관계/기본증명서 원문은 OCR하지 않으며 직원 확인 item으로 남긴다. 검수된 공식 은행 양식의 OCR은 Phase 0에서 호출하지 않는다.

결과는 `register-check.md` 형식을 따르며, `basisDocId`와 정책 근거는 기존 RAG 결과의 `doc_id`, `title`, `section`, `source_pages`, `verified`, `text`를 보존한다. `verified=false` 또는 무근거는 `NEEDS_STAFF_REVIEW`이며 자동 진행 가능이 아니다.
