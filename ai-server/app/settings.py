from pathlib import Path
import os
ROOT=Path(__file__).resolve().parent.parent
try:
    from dotenv import load_dotenv
    load_dotenv(ROOT/'.env')
except ImportError:
    pass
INDEX=ROOT/'.index'/'chunks.json'
VECTOR=ROOT/'.vector_db'
MODE=os.getenv('RAG_MODE','poc').lower()
BACKEND=os.getenv('RAG_BACKEND','chroma').lower()
MODEL=os.getenv('EMBEDDING_MODEL','jhgan/ko-sroberta-multitask')
TASKS={'REPAYMENT_ORDER_CHANGE','HOUSING_SAVINGS_CLOSE','DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT'}
HYBRID_VECTOR_CANDIDATES=int(os.getenv('RAG_VECTOR_CANDIDATES','25'))
HYBRID_LEXICAL_CANDIDATES=int(os.getenv('RAG_LEXICAL_CANDIDATES','25'))
HYBRID_RRF_K=int(os.getenv('RAG_RRF_K','20'))
HYBRID_MAX_PER_SECTION=int(os.getenv('RAG_MAX_PER_SECTION','2'))
HYBRID_EVIDENCE_RRF_WEIGHT=int(os.getenv('RAG_EVIDENCE_RRF_WEIGHT','3'))
SEARCH_TIMEOUT_SECONDS=float(os.getenv('RAG_SEARCH_TIMEOUT_SECONDS','30'))
