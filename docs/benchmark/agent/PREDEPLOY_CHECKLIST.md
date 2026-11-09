# 배포 전 필수 확인사항

## 차단 항목

- [ ] `/api/v1/customer-agent/classify`에 내부 토큰 적용 또는 외부 노출 차단
- [ ] `/api/v1/rag/search`, `/task-guide`, `/answer`를 게이트웨이 뒤에 두거나 인증 적용
- [ ] 직원 LLM 평가 결과 사람 검토 (`results/real/brief_scored.json`의 `humanVerdict`)
- [ ] 고객 의도 분류 실측 (담당 팀원 작업 완료 후)
- [ ] 배포 환경의 `OPENAI_API_KEY` 출처 고정 (사용자 환경변수가 `.env`보다 우선함)
- [ ] 격리 통합 검증 (Spring, FastAPI, MySQL, RAG 연결, DB 영속화, requestId 정합성)
- [ ] origin/main 18커밋 병합과 재검증 (A-02 상세 조회 등이 현재 시연 서버에 없음)
- [ ] Flyway V14 번호 팀원 1 확정

## 배포 산출물

- [ ] 배포용 JAR과 소스 SHA 고정. 현재 18081은 작업 트리 클래스로 실행 중
- [ ] GitHub Actions용 RAG 인덱스 생성 단계 (`python -m scripts.ingest`). `.index`, `.vector_db`는 Git 제외 대상
- [ ] CI에 `VITE_BFF_TASKS_ENABLED=true`, `APP_SMS_PROVIDER=mock` 설정
- [ ] 자동 수신이 필요하면 `APP_POLICY_AUTO_SYNC_ENABLED=true` 명시 (기본값 false)
- [ ] 비밀값은 Secrets로만 주입

## 품질

- [ ] `gpt-4.1-mini` deprecated 표시 확인, 유지 또는 교체 결정
- [ ] `document_type.name`의 한글 표시명 처리
- [ ] 규칙 fallback에 미제출 필수 서류 추가
- [ ] 의도 분류 프롬프트 버전 기록
- [ ] RAG 문서 버전 기록 (RAG-09)
- [ ] `C02-01`, `C01-03` 검수 근거 확인, `retrieval_enabled_prod` 정책 결정
- [ ] 모순 근거 처리 방침 결정 (실측 0/2)
- [ ] 검토 의견 프롬프트 수정: 자동 대조가 없을 때의 고객 초안 표현, 불참 상속인이 없을 때의 위임 항목
- [ ] 복합 요청 처리 범위를 명세와 대조
- [ ] 평가 정답셋 팀원 교차 검토
