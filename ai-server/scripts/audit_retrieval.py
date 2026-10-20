"""Write a human-reviewable, evidence-level vector versus hybrid audit."""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from app.retrieve import search_with_strategy
from app.settings import ROOT


def _relevant(row: dict, test: dict) -> bool:
    if row['doc_id'] not in test['expected_doc_ids']:
        return False
    sections = test.get('expected_sections', [])
    if sections and not any(section in row['section'] for section in sections):
        return False
    evidence = test.get('expected_evidence_terms', [])
    return not evidence or all(term in row.get('context_text', row['text']) for term in evidence)


def _cell(value: str) -> str:
    return value.replace('|', '\\|').replace('\n', ' ')[:180]


def _evidence_state(row: dict, test: dict) -> str:
    terms = test.get('expected_evidence_terms', [])
    if not terms:
        return '근거문구 미지정'
    matched = [term for term in terms if term in row.get('context_text', row['text'])]
    return '일치: ' + ', '.join(matched) if matched else '불일치'


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='tests/retrieval_audit.md')
    args = parser.parse_args()
    tests = json.loads((ROOT / 'tests/questions.json').read_text(encoding='utf8'))
    lines = ['# Retrieval Evidence Audit', '', '관련성은 `expected_doc_ids` + (지정 시) `expected_sections` + (지정 시) 모든 `expected_evidence_terms`가 같은 청크에 존재할 때만 `relevant`입니다.', '']
    for strategy in ('vector', 'hybrid'):
        lines.extend([f'## {strategy}', '', '| 질문 | 순위 | 문서 ID | 섹션 | 기대 근거 일치 | 관련성 | 점수 |', '| --- | ---: | --- | --- | --- | --- | ---: |'])
        for number, test in enumerate(tests, start=1):
            started = time.perf_counter()
            rows = search_with_strategy(test['question'], test['task_code'], test.get('top_k', 5), strategy=strategy)
            elapsed_ms = (time.perf_counter() - started) * 1000
            lines.append(f'| Q{number} [{test["task_code"]}] {_cell(test["question"])} | - | - | - | 응답 {elapsed_ms:.1f}ms | - | - |')
            for rank, row in enumerate(rows, start=1):
                lines.append('|  | ' + str(rank) + ' | ' + _cell(row['doc_id']) + ' | ' + _cell(row['section']) + ' | ' + _cell(_evidence_state(row, test)) + ' | ' + ('relevant' if _relevant(row, test) else 'not relevant') + f' | {row["score"]:.6f} |')
        lines.append('')
    output = ROOT / Path(args.output)
    output.write_text('\n'.join(lines) + '\n', encoding='utf8')
    print(output)


if __name__ == '__main__':
    main()
