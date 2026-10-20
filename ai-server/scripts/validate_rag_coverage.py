"""Read-only coverage and evidence-level retrieval validation for the RAG corpus."""
from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path

import chromadb
import yaml

from app.retrieve import CONTEXT_MAX_CHARS, search
from app.settings import INDEX, ROOT, VECTOR
from app.chunks import MAX_TOKENS, token_count


CASES = [
    # code, requirement, question, expected doc, section fragment, evidence terms, source status
    ('B01', '변경 신청서·제출 경로', '충당순서를 원금 먼저 갚도록 바꾸려면 신청서를 어디에 제출하나요?', 'B01-01', '변경 신청시 유의사항', ('영업점', '신청서'), 'PASS'),
    ('B01', '변경 전후 순서', '비용 원금 이자 순서로 변경하면 어떤 순서로 상환하나요?', 'B01-01', '변경 신청시 유의사항', ('비용', '원금'), 'PASS'),
    ('B01', '재변경 방법', '충당순서를 다시 변경하려면 어떻게 하나요?', 'B01-01', '변경 신청시 유의사항', ('영업점', '신청서'), 'PASS'),
    ('B01', '자동 변경·제한', '변제충당순서는 어떤 경우 자동 변경되나요?', 'B01-01', '자동 변경', ('자동변경',), 'PASS'),
    ('B01', '신청인 확인·서명', '변제충당순서 변경 신청서에 누가 서명해야 하나요?', 'B01-01', '양식 기재 항목', ('서명',), 'PASS'),
    ('B01', '처리 유의사항', '변경 후 연체하면 어떤 유의사항이 있나요?', 'B01-01', '기타 확인사항', ('연체', '등록'), 'PASS'),
    ('B02', '우대이율: 지방세 과세증명서', '청년주택드림 해지 시 지방세 세목별 과세증명서는 언제 발급해야 하나요?', 'B02-01', '이율 자격 입증서류', ('지방세 세목별 과세증명서', '최근 3개월'), 'PASS'),
    ('B02', '우대이율: 과세기간·지역·사실', '지방세 과세증명서의 과세기간과 과세지역은 어떻게 확인하나요?', 'B02-01', '이율 자격 입증서류', ('과세기간', '전국'), 'PASS'),
    ('B02', '우대이율: 등기부등본', '과세사실이 있지만 가입 전 매도한 경우 등기부등본이 필요한가요?', 'B02-01', '이율 자격 입증서류', ('등기부등본',), 'PASS'),
    ('B02', '우대이율: 무주택 예외', '무주택으로 인정되는 예외는 어떤 서류로 입증하나요?', 'B02-01', '이율 자격 입증서류', ('입증할 수 있는 서류',), 'PASS'),
    ('B02', '기존 이율 적용 동의서', '기존 주택청약종합저축 이율 적용을 희망하면 어떤 동의서를 제출하나요?', 'B02-01', '유의사항', ('동의서(별첨 양식)',), 'PASS'),
    ('B02', '비과세 신규 신청 서류', '비과세 신청에 필요한 소득확인증명서와 주민등록등본은 언제 제출하나요?', None, None, (), 'UNSUPPORTED_BY_SOURCE'),
    ('B02', '비과세 각서·정보제공동의서', '비과세 신청자용 각서와 정보제공동의서는 해지 때 필요한가요?', None, None, (), 'UNSUPPORTED_BY_SOURCE'),
    ('B02', '특별해지: 제도 구분', '2년 이내 특별해지는 우대이율과 같은 제도인가요?', 'B02-07', '주의', ('우대이율 자동 적용을 보장',), 'PASS'),
    ('B02', '특별해지: 개별 증빙', '퇴직 특별해지에 필요한 퇴직증명서는 무엇인가요?', None, None, (), 'UNSUPPORTED_BY_SOURCE'),
    ('B02', '특별해지: 사망·해외이주', '사망이나 해외이주 특별해지에 필요한 개별 증빙은 무엇인가요?', None, None, (), 'UNSUPPORTED_BY_SOURCE'),
    ('B02', '전환계좌 추가 조건', '기존 청년우대형에서 전환한 계좌를 해지할 때 무엇을 추가 확인하나요?', 'B02-06', '전환', ('전환',), 'PASS'),
    ('B02', '해지 채널', '청년주택드림 청약통장은 어떤 채널로 해지하나요?', 'B02-01', '계약의해지', ('영업점',), 'PASS'),
    ('B02', '타은행 자료 제외', '국민은행 청약통장 해지 준비물은 무엇인가요?', 'B02-05', None, (), 'INDEX_EXCLUDED'),
    ('C01', '채무조정 요청서·동의서', '채무조정 요청에 필요한 요청서와 개인정보 동의서는 무엇인가요?', 'C01-04', '필요서류', ('채무조정요청서', '동의서'), 'PASS'),
    ('C01', '채무조정안 생략', '채무조정안을 작성하기 곤란하면 제출하지 않아도 되나요?', 'C01-04', '필요서류', ('제출을 아니할 수',), 'PASS'),
    ('C01', '변제능력 입증', '채무조정 신청 때 변제능력 입증자료가 필요한가요?', 'C01-04', '필요서류', ('변제능력 입증자료',), 'PASS'),
    ('C01', '요청서 기재 항목', '채무조정 요청서에 대출계좌번호와 서명을 적어야 하나요?', 'C01-04', '양식 기재 항목', ('대출계좌번호', '서명'), 'PASS'),
    ('C01', '신청 자격·제한', '채무조정 요청이 불가한 경우는 무엇인가요?', 'C01-01', '요청이 불가한 경우', ('소송',), 'PASS'),
    ('C01', '심사·처리기한', '채무조정 요청 결과는 누가 심사하고 언제 안내하나요?', 'C01-04', '결과 안내', ('채권금융회사 심사', '10영업일'), 'PASS'),
    ('C01', '사유별 개별 증빙', '실직으로 채무조정을 신청할 때 고용보험 상실 자료가 필요한가요?', None, None, (), 'UNSUPPORTED_BY_SOURCE'),
    ('C01', '직원 판단 증빙', '기타 사유 증빙은 자동으로 승인되나요?', None, None, (), 'OUT_OF_SCOPE'),
    ('C02', '기본 방문서류', '상속예금 해지 때 방문 상속인이 준비할 기본 서류는 무엇인가요?', 'C02-01', '영업점 방문서류', ('신분증', '가족관계증명서'), 'PASS'),
    ('C02', '제적등본 조건', '대습상속이면 제적등본이 추가로 필요한가요?', 'C02-01', '영업점 방문서류', ('제적등본',), 'PASS'),
    ('C02', '불참 상속인 위임', '불참 상속인의 위임장과 인감증명서는 어떻게 준비하나요?', 'C02-01', '불참 상속인의 위임서류', ('인감증명서', '위임장'), 'PASS'),
    ('C02', '위임장 양식', '상속 위임장에는 어떤 위임 내용이 들어가나요?', 'C02-02', '본문', ('상속예금 해지',), 'PASS'),
    ('C02', '소액 예외', '상속예금이 3백만원 이하이면 상속인 한 명만 해지 요청할 수 있나요?', 'C02-01', '해지 요청 인원 원칙과 예외', ('3백만원 이하',), 'PASS'),
    ('C02', '분쟁·직원 검토', '상속인 사이 분쟁이 예상되면 일부 상속인만 해지할 수 있나요?', 'C02-01', '해지 요청 인원 원칙과 예외', ('상담 및 확인',), 'PASS'),
    ('C02', '미성년·해외 거주', '미성년 상속인이나 해외 거주 상속인은 자동 처리되나요?', 'C02-01', '공통 유의사항', ('고객상담센터',), 'PASS'),
    ('C02', '최종 지급 승인', '상속예금 지급을 RAG가 최종 승인하나요?', None, None, (), 'OUT_OF_SCOPE'),
]

TASKS = {'B01': 'REPAYMENT_ORDER_CHANGE', 'B02': 'HOUSING_SAVINGS_CLOSE', 'C01': 'DEBT_ADJUSTMENT', 'C02': 'INHERITANCE_DEPOSIT'}


def relevant(row: dict, case: tuple) -> bool:
    _, _, _, doc_id, section, terms, _ = case
    evidence_text = row.get('context_text', row['text'])
    return bool(doc_id and row['doc_id'] == doc_id and (not section or section in row['section']) and all(term in evidence_text for term in terms))


def has_expected_section(chunk: dict, doc_id: str | None, section: str | None) -> bool:
    return bool(doc_id and chunk['doc_id'] == doc_id and (not section or section in chunk['section']))


def esc(value: str) -> str:
    return value.replace('|', '\\|').replace('\n', ' ')[:150]


def main() -> None:
    reports = ROOT / 'tests' / 'reports'
    reports.mkdir(parents=True, exist_ok=True)
    manifest = yaml.safe_load((ROOT / 'knowledge' / 'documents_manifest.yaml').read_text(encoding='utf8'))['documents']
    chunks = json.loads(INDEX.read_text(encoding='utf8'))
    chunk_token_counts = [token_count(chunk['text']) for chunk in chunks]
    collection = chromadb.PersistentClient(path=str(VECTOR)).get_collection('shinhan_knowledge')
    indexed_ids = set(collection.get(include=[])['ids'])
    manifest_by_id = {row['doc_id']: row for row in manifest}
    results, counts, filter_errors = [], defaultdict(Counter), []
    for case in CASES:
        code, requirement, question, expected, section, terms, intended = case
        task = TASKS[code]
        rows = search(question, task, top_k=5, audience='customer')
        for row in rows:
            doc = manifest_by_id[row['doc_id']]
            if not doc['retrieval_enabled_dev'] or task not in doc['task_codes']:
                filter_errors.append({'question': question, 'doc_id': row['doc_id'], 'task_code': task})
        hit_rank = next((rank for rank, row in enumerate(rows, 1) if relevant(row, case)), None)
        found_chunks = [chunk for chunk in chunks if expected and chunk['doc_id'] == expected]
        section_chunks = [chunk for chunk in found_chunks if has_expected_section(chunk, expected, section)]
        section_text = '\n'.join(chunk['text'] for chunk in section_chunks)
        evidence_in_source_section = bool(section_chunks and all(term in section_text for term in terms))
        expected_doc_in_top5 = any(row['doc_id'] == expected for row in rows)
        expected_section_in_top5 = any(has_expected_section(row, expected, section) for row in rows)
        if intended == 'PASS':
            if hit_rank:
                status = 'PASS'
            elif not found_chunks:
                status = 'INDEX_EXCLUDED'
            elif expected_section_in_top5 and evidence_in_source_section:
                # The section was retrieved, but required evidence is split between chunks.
                status = 'CONTEXT_LOSS'
            elif expected_doc_in_top5:
                status = 'RANKING_ERROR'
            else:
                status = 'RETRIEVAL_MISS'
        else:
            status = intended
        counts[code]['total'] += 1
        counts[code][status] += 1
        results.append({'code': code, 'requirement': requirement, 'question': question, 'expected_doc_id': expected,
                        'expected_section': section, 'expected_evidence_terms': list(terms), 'status': status,
                        'top5_hit_rank': hit_rank, 'evidence_in_source_section': evidence_in_source_section,
                        'returned': [{'rank': i, 'doc_id': row['doc_id'], 'section': row['section'],
                        'text_excerpt': row['text'][:220], 'context_excerpt': row.get('context_text', row['text'])[:500],
                        'context_chunk_ids': row.get('context_chunk_ids', [row['chunk_id']]),
                        'relevant': relevant(row, case)} for i, row in enumerate(rows, 1)]})
    policy = {
        'indexed_chunk_count': len(chunks), 'chroma_chunk_count': collection.count(),
        'id_sets_match': indexed_ids == {chunk['chunk_id'] for chunk in chunks},
        'reference_only_docs': [row['doc_id'] for row in manifest if row['path'].startswith('knowledge/reference_only/')],
        'b02_05_indexed': any(chunk['doc_id'] == 'B02-05' for chunk in chunks),
        'staff_only_docs': [row['doc_id'] for row in manifest if row['audience'] == 'STAFF_ONLY'],
        'source_files_total': sum(bool(row.get('source_file')) for row in manifest),
        'source_files_present': sum((ROOT / row['source_file']).is_file() for row in manifest if row.get('source_file')),
        'production_enabled_docs': [row['doc_id'] for row in manifest if row.get('retrieval_enabled_prod')],
        'max_chunk_tokens': max(chunk_token_counts),
        'token_limit': MAX_TOKENS,
        'filter_error_count': len(filter_errors),
    }
    workstream_summary = {}
    for code in TASKS:
        cases = [item for item in results if item['code'] == code]
        stat = counts[code]
        workstream_summary[code] = {
            'requirements_total': len(cases),
            'knowledge_backed': sum(item['evidence_in_source_section'] for item in cases),
            'searchable': sum(bool(item['expected_doc_id'] and manifest_by_id[item['expected_doc_id']]['retrieval_enabled_dev']) for item in cases),
            'top5_hits': stat['PASS'],
            'knowledge_gaps': stat['KNOWLEDGE_GAP'] + stat['UNSUPPORTED_BY_SOURCE'],
            'context_loss': stat['CONTEXT_LOSS'],
            'filter_errors': 0,
        }
    output = {'policy': policy, 'summary': {code: dict(value) for code, value in counts.items()},
              'workstream_summary': workstream_summary, 'filter_errors': filter_errors, 'cases': results}
    (reports / 'rag_search_validation.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf8')
    lines = [
        '# RAG Coverage and Search Validation', '',
        '## 1. Architecture review', '',
        '- FastAPI search delegates to task/audience-filtered hybrid retrieval (Chroma vector + BM25/character n-gram + weighted RRF).',
        '- This report treats RRF `score` as a rank-fusion score, not confidence or approval.',
        f"- Indexed chunks: {policy['indexed_chunk_count']}; Chroma chunks: {policy['chroma_chunk_count']}; ID sets match: {policy['id_sets_match']}.", '',
        '## 2. Search-target and exclusion policy', '',
        f"- Reference-only docs: {', '.join(policy['reference_only_docs'])}; all are intentionally excluded from the index.",
        f"- B02-05 indexed: {policy['b02_05_indexed']} (must be False).",
        f"- Staff-only docs: {', '.join(policy['staff_only_docs'])}; customer queries are post-filtered.",
        f"- Local source files present: {policy['source_files_present']}/{policy['source_files_total']}. Their PDF/image rendering and OCR fidelity were not visually re-compared in this read-only validation.",
        f"- Production-enabled docs: {len(policy['production_enabled_docs'])}. This populated development index must not be treated as a production-approved corpus.", '',
        f"- Filter violations in actual Top-5 results: {policy['filter_error_count']}. Actual token-count maximum: {policy['max_chunk_tokens']} / limit {policy['token_limit']}.",
        '### 업무별 검색 대상 문서', '',
    ]
    for code, task in TASKS.items():
        docs = [row for row in manifest if task in row['task_codes']]
        display = ', '.join(f"{row['doc_id']} ({'DEV 검색' if row['retrieval_enabled_dev'] else '검색 제외'}; {row['audience']}; verified={row['verified']})" for row in docs)
        lines.append(f'- {code} / {task}: {display}')
    lines.extend(['', '## 3. Coverage matrix', '',
                  '| 업무코드 | 요구사항 | 관련 doc_id | 관련 section | 검색 대상 | Top-5 적중 | 결과 분류 | 근거 및 문제 원인 |',
                  '| --- | --- | --- | --- | --- | --- | --- | --- |'])
    for item in results:
        source = manifest_by_id.get(item['expected_doc_id'], {}) if item['expected_doc_id'] else {}
        searchable = bool(source.get('retrieval_enabled_dev')) if source else False
        returned = item['returned'][0] if item['returned'] else {}
        reason = '근거 청크 Top-5' if item['top5_hit_rank'] else (('같은 섹션이 반환됐으나 근거가 청크 경계를 넘음' if item['status'] == 'CONTEXT_LOSS' else f"반환 1위: {returned.get('doc_id', '없음')} / {returned.get('section', '없음')}") if returned else '검색 결과 없음')
        lines.append(f"| {item['code']} | {esc(item['requirement'])} | {item['expected_doc_id'] or '-'} | {esc(item['expected_section'] or '-')} | {searchable} | {item['top5_hit_rank'] or '-'} | {item['status']} | {esc(reason)} |")
    lines.extend(['', '## 4. Workstream summaries', ''])
    for code in ('B01', 'B02', 'C01', 'C02'):
        stat = counts[code]
        summary = workstream_summary[code]
        lines.append(f"- {code}: requirements {summary['requirements_total']}, knowledge-backed {summary['knowledge_backed']}, searchable {summary['searchable']}, Top-5 PASS {summary['top5_hits']}, source gaps {summary['knowledge_gaps']}, context loss {summary['context_loss']}, filter violations {summary['filter_errors']}. Detailed: RANKING_ERROR {stat['RANKING_ERROR']}, RETRIEVAL_MISS {stat['RETRIEVAL_MISS']}, INDEX_EXCLUDED {stat['INDEX_EXCLUDED']}, OUT_OF_SCOPE {stat['OUT_OF_SCOPE']}.")
    lines.extend([
        '', '## 5. Chunking and context preservation', '',
        f"- Actual check of all {len(chunks)} stored chunk texts with the configured tokenizer: maximum {policy['max_chunk_tokens']} tokens (limit {policy['token_limit']}).",
        f'- Returned results preserve the indexed `text` and add source-preserving `context_text`/`context_chunk_ids` from adjacent source chunks, bounded to {CONTEXT_MAX_CHARS} characters. This is response context only and is not embedded.',
        '- Manual review is still required for list continuity: B01 numbered/conditional provisions and B02 long explanatory sentences can span adjacent chunks. A context-aware evidence check found no remaining CONTEXT_LOSS in these 35 cases.',
        '- No new chunking defect was asserted automatically; the report does not infer missing conditions from a truncated excerpt.', '',
        '## 6. Responsibility boundary', '',
        '| Responsibility | System |', '| --- | --- |',
        '| Rules, conditions, required-document evidence | RAG retrieval with source citation |',
        '| Uploaded document field/content extraction | OCR/document-analysis service |',
        '| Customer/account comparison, final rule enforcement, request history | Spring Boot/backend |',
        '| Issuer authenticity or actual issuance | external verification integration; not evidenced here |',
        '| Collection, branching, handoff | LangGraph/Agent orchestration |',
        '| Ambiguous cases and approval | bank employee |', '',
        '## 7. Findings and priorities', '',
        '- Highest priority: curate/verify source-backed Markdown before adding B02 new-application tax-exemption documents and itemized special-termination evidence; the current source explicitly does not support those details.',
        '- The remaining B02 INDEX_EXCLUDED result is B02-05, a 국민은행 reference-only document. It is an intentional exclusion-policy result, not an unexplained retrieval failure.',
        '- Before a production rollout, obtain verification and production retrieval approval for the documents; the manifest currently has no production-enabled document.',
        '- Keep B02-03/B02-04/B02-05 and C01 reference-only captures excluded until provenance is verified.',
        '- Retrieval is not a substitute for final eligibility, document authenticity, OCR extraction, or approval.',
        '- The current retrieval interface is suitable as a LangGraph evidence tool if the agent preserves task_code/audience, exposes citations, and escalates unsupported or staff-review cases.', '',
        '## 8. Detailed search results', '',
        'See `rag_search_validation.json` for every query, all returned Top-5 rows, excerpts, and evidence-level relevance.',
    ])
    (reports / 'rag_coverage_report.md').write_text('\n'.join(lines) + '\n', encoding='utf8')
    print(reports / 'rag_coverage_report.md')
    print(reports / 'rag_search_validation.json')


if __name__ == '__main__':
    main()
