import json
from app.settings import ROOT
from app.retrieve import search

def main():
    tests=json.loads((ROOT/'tests/questions.json').read_text(encoding='utf8'))
    misses=[]
    for test in tests:
        got=search(test['question'],test['task_code'],top_k=test.get('top_k',5))
        ids=list(dict.fromkeys(x['doc_id'] for x in got))
        hit=bool(set(test['expected_doc_ids']) & set(ids))
        evidence=test.get('expected_evidence_terms',[])
        evidence_hit=not evidence or any(all(term in row.get('context_text', row['text']) for term in evidence) for row in got)
        sections=test.get('expected_sections',[])
        section_hit=not sections or any(any(section in row['section'] for section in sections) for row in got)
        excluded=set(test.get('excluded_doc_ids',[]))
        no_leak=not any(row['doc_id'] in excluded or row['task_code']!=test['task_code'] for row in got)
        hit=hit and evidence_hit and section_hit and no_leak
        print(('HIT' if hit else 'MISS'),test['task_code'],test['question'],'=>',ids[:3])
        if not hit:misses.append(test)
    if misses:raise SystemExit(f'{len(misses)} smoke test(s) missed expected documents')

if __name__=='__main__': main()
