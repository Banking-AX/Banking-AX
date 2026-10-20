# RAG HTTP API / LangGraph Tool Integration Readiness

## Verdict

**Ready for a context-aware retrieval Tool in the current PoC boundary.**
`POST /api/v1/rag/search` now publishes and returns the traceable context
fields required by an Agent. This does not make the PoC corpus production
approved: returned evidence remains `verified: false`.

No RAG ranking, chunking, embeddings, Chroma collection, Markdown, or manifest
was changed in this API-contract work.

## 1. HTTP contract

| Check | Result |
| --- | --- |
| Explicit request model | PASS — `query`, `task_code`, `audience`, `top_k` retained. |
| Explicit response model | PASS — `SearchResponse` and `SearchResult` are the route `response_model`. |
| Existing result fields | PASS — `chunk_id`, `doc_id`, `title`, `section`, `text`, `score`, `source_file`, `source_pages`, `verified` retained. |
| Extended context fields | PASS — `context_text`, `context_chunk_ids`, `context_sections` returned. |
| OpenAPI 200 schema | PASS — `/openapi.json` references `#/components/schemas/SearchResponse`; `SearchResult` declares all 12 result fields. |
| Empty search result | PASS — a valid empty list serializes as `200 { "results": [] }`; tested with a mocked retriever. |

## 2. Actual HTTP integration test

Started local `uvicorn` on `127.0.0.1:9011`, called `/health`, then stopped it
after the tests. All representative customer requests returned HTTP 200 and
three results with all context fields.

| Task | Result documents | Context field / trace check | B02-05 |
| --- | --- | --- | --- |
| B01 / `REPAYMENT_ORDER_CHANGE` | B01-01, CM-01, B01-01 | present; first trace has 3 chunk IDs | not returned |
| B02 / `HOUSING_SAVINGS_CLOSE` | B02-01 × 3 | present; first trace has 7 chunk IDs | not returned |
| C01 / `DEBT_ADJUSTMENT` | C01-04 × 3 | present; first trace has 2 chunk IDs | not returned |
| C02 / `INHERITANCE_DEPOSIT` | C02-02, C02-02, C02-01 | present; first trace has 1 chunk ID | not returned |

The result preserves the indexed `text` anchor. `context_text` is an
additional, source-preserving context join. `context_chunk_ids` and
`context_sections` let a Tool retain citations rather than treating expanded
text as untraceable prose.

## 3. Validation and error contract

| Scenario | Status | Machine code | Test method |
| --- | ---: | --- | --- |
| Invalid task, audience, empty query, `top_k` 0/9 | 422 | `INVALID_REQUEST` | FastAPI integration test |
| Normal zero results | 200 | N/A, `results: []` | mocked retriever |
| Retrieval timeout | 504 | `RETRIEVAL_TIMEOUT` | mocked timeout |
| Chroma/vector backend failure | 503 | `VECTOR_DB_UNAVAILABLE` | mocked backend exception |
| Embedding-model failure | 503 | `EMBEDDING_MODEL_ERROR` | mocked model exception |
| Other retrieval failure | 500 | `INTERNAL_ERROR` | mocked exception; message does not expose exception text |

Search execution uses `RAG_SEARCH_TIMEOUT_SECONDS` (default 30 seconds). A
timed-out blocking library call is allowed to unwind in its worker while the
HTTP request returns 504; it is not forcibly terminated.

## 4. Filter and context safety

Existing retrieval checks remain valid: task/audience/reference-only filtering
is performed before context expansion. The prior 17-query Hybrid context audit
checked 85 returned rows: all context IDs belonged to the same document, task,
and section as their anchor; all source order and exact source joins matched;
the longest context was 1,597 of 1,600 characters. B02-05 did not appear.

## 5. Regression results

| Test | Result |
| --- | --- |
| API contract unit/integration tests | 8/8 passed |
| 35-question coverage validation | 27 PASS; remaining cases are source-unsupported, intentionally excluded, or out of RAG scope |
| 17-question vector evaluation | Recall@5 17/17 |
| 17-question hybrid evaluation | Recall@5 17/17 |
| Existing smoke test | 17/17 HIT |
| Chroma/index and token checks | 242/242 IDs match; maximum 128 tokens |

## 6. LangGraph Tool handoff

A Tool can send `task_code` and `audience` explicitly and consume `results`
with source file, page, document ID, verified status, anchor text, and
traceable expanded context. It must preserve the following safeguards:

1. Treat a non-empty result list as candidate evidence, not an answer-quality
   assertion; no relevance threshold was introduced because no calibrated
   evaluation data exists.
2. Ask a follow-up question, abstain, or route to a human when citations do not
   support the requested claim.
3. Treat `verified: false` and the PoC verification notice as non-production
   evidence; never convert them into an approval or eligibility decision.
4. Handle 422, 500, 503, and 504 by their error code and `retryable` flag.

Remaining follow-up items are policy/operational rather than API-contract
blockers: define calibrated no-evidence behavior, production verification, and
observability/cancellation for long-running backend work. No LangGraph code is
implemented here.

## Reproduction (Windows CMD)

```cmd
cd /d C:\Users\supil\Shinhan_TeamProject\Banking-AX\ai-server
set HF_HUB_OFFLINE=1
python -m unittest tests.test_rag_api -v
python -m uvicorn app.main:app --host 127.0.0.1 --port 9000
```

In another CMD window:

```cmd
curl -X POST http://127.0.0.1:9000/api/v1/rag/search -H "Content-Type: application/json" -d "{\"query\":\"채무조정 요청서에 대출계좌번호와 서명을 적어야 하나요?\",\"task_code\":\"DEBT_ADJUSTMENT\",\"audience\":\"customer\",\"top_k\":3}"
curl http://127.0.0.1:9000/openapi.json
```
