from __future__ import annotations
from collections import Counter
from pathlib import Path
from app.settings import ROOT
from app.chunks import MAX_TOKENS, build_chunks, token_count
import yaml

def main():
    manifest=yaml.safe_load((ROOT/'knowledge/documents_manifest.yaml').read_text(encoding='utf8'))['documents']
    assert len({x['doc_id'] for x in manifest})==len(manifest),'duplicate doc_id'

    for d in manifest:
        assert (ROOT/d['path']).is_file(),d['path']
        assert (ROOT/d['source_file']).is_file(),d['source_file']

        if d['path'].startswith('knowledge/reference_only/'):
            assert not d['retrieval_enabled_dev'] and not d['retrieval_enabled_prod'],d['doc_id']

        if d['retrieval_enabled_prod']:
            assert d['verified'] and d['source_verified'],d['doc_id']

    chunks=build_chunks('poc')

    assert chunks,'poc chunks should not be empty'
    assert len(set(x['chunk_id'] for x in chunks))==len(chunks),'duplicate chunk_id'
    assert all(not x['doc_id'].startswith('B02-05') for x in chunks),'cross-bank reference leak'
    counts=[token_count(x['text']) for x in chunks]
    assert max(counts)<=MAX_TOKENS,f'chunk token limit exceeded: {max(counts)}'
    evidence=' '.join(x['text'] for x in chunks if x['doc_id']=='B02-01')
    for phrase in ('지방세 세목별 과세증명서','등기부등본','무주택으로 인정','입증할 수 있는 서류'):
        assert phrase in evidence,f'B02-01 evidence missing: {phrase}'

    print('PASS docs:',len(manifest),'POC chunks:',len(chunks))
    print('By task:', dict(Counter(x['task_code'] for x in chunks)))
    print('Excluded reference-only:',len([d for d in manifest if not d['retrieval_enabled_dev']]))
    print('Production documents ready:',len([d for d in manifest if d['retrieval_enabled_prod']]))
    print('Max embedding tokens:',max(counts))

if __name__=='__main__':main()
