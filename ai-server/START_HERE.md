# Banking-AX / ai-server - RAG 빠른 실행

이 ZIP은 `Banking-AX/ai-server/` 안으로 압축해제합니다. 새 `ai-server` 폴더를 중첩해서 만들지 마세요.

## RAG 파일 구성
- `knowledge/curated` 검색 후보 MD 12개 (원본 전체 대조 미완료, PoC만)
- `knowledge/reference_only` 검색 금지 MD 5개
- `knowledge/sources` 로컬 검증용 PDF·캡처 (공개 Git 커밋 금지)
- `knowledge/documents_manifest.yaml` 문서 등록·인덱싱 설정
- `playbooks` Agent 상담 흐름 3개 (RAG 미적재)
- `app/main.py`, `app/chunks.py`, `app/retrieve.py`, `app/settings.py` FastAPI 검색 API
- `scripts` 인덱싱·검증

## 명령어 (VS Code PowerShell)
```powershell
# Banking-AX 루트에 있는 경우
Expand-Archive -Path "$env:USERPROFILE\Downloads\Banking_AX_RAG_바로배치.zip" -DestinationPath ".\ai-server" -Force
cd ai-server
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-lite.txt
$env:RAG_BACKEND='keyword'
python -m scripts.validate
python -m scripts.ingest --mode poc --backend keyword
python -m scripts.smoke_test
uvicorn app.main:app --reload --port 9000
```

접속: http://127.0.0.1:9000/docs → `POST /api/v1/rag/search`

실제 벡터DB (Chroma) 전환은 `python -m pip install -r requirements-vector.txt` 설치 후 `$env:RAG_BACKEND='chroma'; python -m scripts.ingest --mode poc --backend chroma`를 실행합니다. 모델 최초 다운로드에 시간이 소요됩니다.

현재 `verified: false` 파일들이 있으므로 실서비스 상담/자동판정에 사용하지 마세요. 금융업무 조건 검증은 Spring이 담당합니다.
