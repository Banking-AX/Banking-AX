"""Compare vector-only and hybrid RAG retrieval on evidence-backed test cases."""
from __future__ import annotations

import argparse
import json
import time
from collections import defaultdict

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


def _evaluate(strategy: str, tests: list[dict]) -> tuple[dict, list[dict]]:
    by_task = defaultdict(lambda: {'tests': 0, 'recall': 0.0, 'mrr': 0.0, 'precision': 0.0, 'elapsed_ms': 0.0})
    details = []
    for test in tests:
        started = time.perf_counter()
        rows = search_with_strategy(test['question'], test['task_code'], test.get('top_k', 5), strategy=strategy)
        elapsed_ms = (time.perf_counter() - started) * 1000
        relevant_ranks = [rank for rank, row in enumerate(rows, start=1) if _relevant(row, test)]
        excluded = set(test.get('excluded_doc_ids', []))
        leaked = [row['doc_id'] for row in rows if row['doc_id'] in excluded or row['task_code'] != test['task_code']]
        recall = float(bool(relevant_ranks))
        mrr = 1 / relevant_ranks[0] if relevant_ranks else 0.0
        precision = sum(_relevant(row, test) for row in rows) / max(1, len(rows))
        metrics = by_task[test['task_code']]
        metrics['tests'] += 1
        metrics['recall'] += recall
        metrics['mrr'] += mrr
        metrics['precision'] += precision
        metrics['elapsed_ms'] += elapsed_ms
        details.append({
            'task_code': test['task_code'], 'question': test['question'], 'recall_at_k': recall,
            'mrr_at_k': mrr, 'precision_at_k': precision, 'elapsed_ms': elapsed_ms,
            'relevant_ranks': relevant_ranks, 'leaked_doc_ids': leaked,
            'result_ids': [row['doc_id'] for row in rows],
        })
    summary = {}
    for task, metrics in by_task.items():
        total = metrics['tests']
        summary[task] = {
            'tests': total,
            'recall_at_5': round(metrics['recall'] / total, 4),
            'mrr_at_5': round(metrics['mrr'] / total, 4),
            'precision_at_5': round(metrics['precision'] / total, 4),
            'avg_elapsed_ms': round(metrics['elapsed_ms'] / total, 2),
        }
    return summary, details


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default=None, help='Optional JSON report path')
    args = parser.parse_args()
    tests = json.loads((ROOT / 'tests/questions.json').read_text(encoding='utf8'))
    report = {}
    for strategy in ('vector', 'hybrid'):
        summary, details = _evaluate(strategy, tests)
        report[strategy] = {'summary': summary, 'details': details}
        print(f'[{strategy}]')
        for task, metrics in summary.items():
            print(task, metrics)
        failures = [detail for detail in details if not detail['recall_at_k'] or detail['leaked_doc_ids']]
        print('Failures:', len(failures))
        for detail in failures:
            print('-', detail['task_code'], detail['question'], '=>', detail['result_ids'], detail['leaked_doc_ids'])
    if args.output:
        path = ROOT / args.output
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
        print('Wrote', path)


if __name__ == '__main__':
    main()
