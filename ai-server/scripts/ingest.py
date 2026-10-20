from __future__ import annotations
import argparse,json,os
from . import __init__
from app.settings import INDEX,VECTOR,MODE,BACKEND,MODEL
from app.chunks import build_chunks

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['poc','production'],default=MODE)

    parser.add_argument('--backend',choices=['keyword','chroma'],default=BACKEND)

    args=parser.parse_args()

    chunks=build_chunks(args.mode)

    if not chunks:raise SystemExit('No indexable documents: validate document manifest first.')

    INDEX.parent.mkdir(exist_ok=True)
    INDEX.write_text(json.dumps(chunks,ensure_ascii=False,indent=2),encoding='utf8')
    print(f'Prepared {len(chunks)} chunks from {len({c["doc_id"] for c in chunks})} documents; mode={args.mode}')

    if args.backend=='keyword':
        print('Keyword smoke-test index ready. Chroma is not loaded.');return


    import chromadb
    from sentence_transformers import SentenceTransformer
    print('Loading embedding model (first run may download model weights):',MODEL)
    model=SentenceTransformer(MODEL)
    client=chromadb.PersistentClient(path=str(VECTOR))
    # Recreate only the RAG collection so old embeddings can never survive a re-chunk.

    try:
        client.delete_collection('shinhan_knowledge')
    except chromadb.errors.NotFoundError:
        pass  # First indexing run: the collection does not exist yet.
    collection=client.get_or_create_collection('shinhan_knowledge',metadata={'hnsw:space':'cosine'})

    for offset in range(0,len(chunks),128):
        batch=chunks[offset:offset+128]
        embeddings=model.encode([c['text'] for c in batch],normalize_embeddings=True,show_progress_bar=False)

        if embeddings.shape[1] != 768:
            raise RuntimeError(f'Expected 768-dimensional embeddings, got {embeddings.shape[1]}')

        collection.add(ids=[c['chunk_id'] for c in batch],documents=[c['text'] for c in batch],
            embeddings=[v.tolist() for v in embeddings],
            metadatas=[{'doc_id':c['doc_id'],'task_code':c['task_code'],
                'audience':c['audience'],'source_file':c['source_file'],
                'section':c['section']} for c in batch])

    print('Chroma ready:',collection.count(),'chunks; embedding dimensions:',embeddings.shape[1])

if __name__=='__main__':main()
