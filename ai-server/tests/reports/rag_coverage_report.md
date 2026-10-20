# RAG Coverage and Search Validation

## 1. Architecture review

- FastAPI search delegates to task/audience-filtered hybrid retrieval (Chroma vector + BM25/character n-gram + weighted RRF).
- This report treats RRF `score` as a rank-fusion score, not confidence or approval.
- Indexed chunks: 242; Chroma chunks: 242; ID sets match: True.

## 2. Search-target and exclusion policy

- Reference-only docs: B02-03, B02-04, B02-05, C01-02, C01-06; all are intentionally excluded from the index.
- B02-05 indexed: False (must be False).
- Staff-only docs: C01-03, C01-05; customer queries are post-filtered.
- Local source files present: 17/17. Their PDF/image rendering and OCR fidelity were not visually re-compared in this read-only validation.
- Production-enabled docs: 0. This populated development index must not be treated as a production-approved corpus.

- Filter violations in actual Top-5 results: 0. Actual token-count maximum: 128 / limit 128.
### 업무별 검색 대상 문서

- B01 / REPAYMENT_ORDER_CHANGE: B01-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), CM-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False)
- B02 / HOUSING_SAVINGS_CLOSE: B02-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), B02-02 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), B02-03 (검색 제외; REFERENCE_ONLY; verified=False), B02-04 (검색 제외; REFERENCE_ONLY; verified=False), B02-05 (검색 제외; REFERENCE_ONLY; verified=False), B02-06 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), B02-07 (DEV 검색; CUSTOMER_AND_STAFF; verified=False)
- C01 / DEBT_ADJUSTMENT: C01-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), C01-02 (검색 제외; REFERENCE_ONLY; verified=False), C01-03 (DEV 검색; STAFF_ONLY; verified=False), C01-04 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), C01-05 (DEV 검색; STAFF_ONLY; verified=False), C01-06 (검색 제외; REFERENCE_ONLY; verified=False), CM-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False)
- C02 / INHERITANCE_DEPOSIT: C02-01 (DEV 검색; CUSTOMER_AND_STAFF; verified=False), C02-02 (DEV 검색; CUSTOMER_AND_STAFF; verified=False)

## 3. Coverage matrix

| 업무코드 | 요구사항 | 관련 doc_id | 관련 section | 검색 대상 | Top-5 적중 | 결과 분류 | 근거 및 문제 원인 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B01 | 변경 신청서·제출 경로 | B01-01 | 변경 신청시 유의사항 | True | 1 | PASS | 근거 청크 Top-5 |
| B01 | 변경 전후 순서 | B01-01 | 변경 신청시 유의사항 | True | 1 | PASS | 근거 청크 Top-5 |
| B01 | 재변경 방법 | B01-01 | 변경 신청시 유의사항 | True | 1 | PASS | 근거 청크 Top-5 |
| B01 | 자동 변경·제한 | B01-01 | 자동 변경 | True | 1 | PASS | 근거 청크 Top-5 |
| B01 | 신청인 확인·서명 | B01-01 | 양식 기재 항목 | True | 2 | PASS | 근거 청크 Top-5 |
| B01 | 처리 유의사항 | B01-01 | 기타 확인사항 | True | 3 | PASS | 근거 청크 Top-5 |
| B02 | 우대이율: 지방세 과세증명서 | B02-01 | 이율 자격 입증서류 | True | 1 | PASS | 근거 청크 Top-5 |
| B02 | 우대이율: 과세기간·지역·사실 | B02-01 | 이율 자격 입증서류 | True | 1 | PASS | 근거 청크 Top-5 |
| B02 | 우대이율: 등기부등본 | B02-01 | 이율 자격 입증서류 | True | 1 | PASS | 근거 청크 Top-5 |
| B02 | 우대이율: 무주택 예외 | B02-01 | 이율 자격 입증서류 | True | 2 | PASS | 근거 청크 Top-5 |
| B02 | 기존 이율 적용 동의서 | B02-01 | 유의사항 | True | 1 | PASS | 근거 청크 Top-5 |
| B02 | 비과세 신규 신청 서류 | - | - | False | - | UNSUPPORTED_BY_SOURCE | 반환 1위: B02-01 / 2. 거래 조건: 적용이율 > 「청년주택드림청약통장」 이율 자격 입증서류(저축 해지 시 제출) |
| B02 | 비과세 각서·정보제공동의서 | - | - | False | - | UNSUPPORTED_BY_SOURCE | 반환 1위: B02-02 / 6. 동의서 > 본문 |
| B02 | 특별해지: 제도 구분 | B02-07 | 주의 | True | 3 | PASS | 근거 청크 Top-5 |
| B02 | 특별해지: 개별 증빙 | - | - | False | - | UNSUPPORTED_BY_SOURCE | 반환 1위: B02-07 / 2년 이내 특별해지와 비과세 추징 예외 (상품설명서 6쪽) |
| B02 | 특별해지: 사망·해외이주 | - | - | False | - | UNSUPPORTED_BY_SOURCE | 반환 1위: B02-01 / 2. 거래 조건: 적용이율 > 「청년주택드림청약통장」 이율 자격 입증서류(저축 해지 시 제출) |
| B02 | 전환계좌 추가 조건 | B02-06 | 전환 | True | 1 | PASS | 근거 청크 Top-5 |
| B02 | 해지 채널 | B02-01 | 계약의해지 | True | 2 | PASS | 근거 청크 Top-5 |
| B02 | 타은행 자료 제외 | B02-05 | - | False | - | INDEX_EXCLUDED | 반환 1위: B02-01 / 3. 유의사항: 기타 유의사항 (발췌) |
| C01 | 채무조정 요청서·동의서 | C01-04 | 필요서류 | True | 1 | PASS | 근거 청크 Top-5 |
| C01 | 채무조정안 생략 | C01-04 | 필요서류 | True | 1 | PASS | 근거 청크 Top-5 |
| C01 | 변제능력 입증 | C01-04 | 필요서류 | True | 1 | PASS | 근거 청크 Top-5 |
| C01 | 요청서 기재 항목 | C01-04 | 양식 기재 항목 | True | 1 | PASS | 근거 청크 Top-5 |
| C01 | 신청 자격·제한 | C01-01 | 요청이 불가한 경우 | True | 2 | PASS | 근거 청크 Top-5 |
| C01 | 심사·처리기한 | C01-04 | 결과 안내 | True | 1 | PASS | 근거 청크 Top-5 |
| C01 | 사유별 개별 증빙 | - | - | False | - | UNSUPPORTED_BY_SOURCE | 반환 1위: CM-01 / 11. 기타 유의사항 (발췌) > 채무조정요청권 |
| C01 | 직원 판단 증빙 | - | - | False | - | OUT_OF_SCOPE | 반환 1위: C01-04 / 채무조정 요청관련 안내사항 > 필요서류 |
| C02 | 기본 방문서류 | C02-01 | 영업점 방문서류 | True | 2 | PASS | 근거 청크 Top-5 |
| C02 | 제적등본 조건 | C02-01 | 영업점 방문서류 | True | 1 | PASS | 근거 청크 Top-5 |
| C02 | 불참 상속인 위임 | C02-01 | 불참 상속인의 위임서류 | True | 1 | PASS | 근거 청크 Top-5 |
| C02 | 위임장 양식 | C02-02 | 본문 | True | 2 | PASS | 근거 청크 Top-5 |
| C02 | 소액 예외 | C02-01 | 해지 요청 인원 원칙과 예외 | True | 1 | PASS | 근거 청크 Top-5 |
| C02 | 분쟁·직원 검토 | C02-01 | 해지 요청 인원 원칙과 예외 | True | 1 | PASS | 근거 청크 Top-5 |
| C02 | 미성년·해외 거주 | C02-01 | 공통 유의사항 | True | 1 | PASS | 근거 청크 Top-5 |
| C02 | 최종 지급 승인 | - | - | False | - | OUT_OF_SCOPE | 반환 1위: C02-01 / 해지 요청 인원 원칙과 예외 |

## 4. Workstream summaries

- B01: requirements 6, knowledge-backed 6, searchable 6, Top-5 PASS 6, source gaps 0, context loss 0, filter violations 0. Detailed: RANKING_ERROR 0, RETRIEVAL_MISS 0, INDEX_EXCLUDED 0, OUT_OF_SCOPE 0.
- B02: requirements 13, knowledge-backed 8, searchable 8, Top-5 PASS 8, source gaps 4, context loss 0, filter violations 0. Detailed: RANKING_ERROR 0, RETRIEVAL_MISS 0, INDEX_EXCLUDED 1, OUT_OF_SCOPE 0.
- C01: requirements 8, knowledge-backed 6, searchable 6, Top-5 PASS 6, source gaps 1, context loss 0, filter violations 0. Detailed: RANKING_ERROR 0, RETRIEVAL_MISS 0, INDEX_EXCLUDED 0, OUT_OF_SCOPE 1.
- C02: requirements 8, knowledge-backed 7, searchable 7, Top-5 PASS 7, source gaps 0, context loss 0, filter violations 0. Detailed: RANKING_ERROR 0, RETRIEVAL_MISS 0, INDEX_EXCLUDED 0, OUT_OF_SCOPE 1.

## 5. Chunking and context preservation

- Actual check of all 242 stored chunk texts with the configured tokenizer: maximum 128 tokens (limit 128).
- Returned results preserve the indexed `text` and add source-preserving `context_text`/`context_chunk_ids` from adjacent source chunks, bounded to 1600 characters. This is response context only and is not embedded.
- Manual review is still required for list continuity: B01 numbered/conditional provisions and B02 long explanatory sentences can span adjacent chunks. A context-aware evidence check found no remaining CONTEXT_LOSS in these 35 cases.
- No new chunking defect was asserted automatically; the report does not infer missing conditions from a truncated excerpt.

## 6. Responsibility boundary

| Responsibility | System |
| --- | --- |
| Rules, conditions, required-document evidence | RAG retrieval with source citation |
| Uploaded document field/content extraction | OCR/document-analysis service |
| Customer/account comparison, final rule enforcement, request history | Spring Boot/backend |
| Issuer authenticity or actual issuance | external verification integration; not evidenced here |
| Collection, branching, handoff | LangGraph/Agent orchestration |
| Ambiguous cases and approval | bank employee |

## 7. Findings and priorities

- Highest priority: curate/verify source-backed Markdown before adding B02 new-application tax-exemption documents and itemized special-termination evidence; the current source explicitly does not support those details.
- The remaining B02 INDEX_EXCLUDED result is B02-05, a 국민은행 reference-only document. It is an intentional exclusion-policy result, not an unexplained retrieval failure.
- Before a production rollout, obtain verification and production retrieval approval for the documents; the manifest currently has no production-enabled document.
- Keep B02-03/B02-04/B02-05 and C01 reference-only captures excluded until provenance is verified.
- Retrieval is not a substitute for final eligibility, document authenticity, OCR extraction, or approval.
- The current retrieval interface is suitable as a LangGraph evidence tool if the agent preserves task_code/audience, exposes citations, and escalates unsupported or staff-review cases.

## 8. Detailed search results

See `rag_search_validation.json` for every query, all returned Top-5 rows, excerpts, and evidence-level relevance.
