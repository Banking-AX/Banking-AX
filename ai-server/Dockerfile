
FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# OpenCV 등 실행에 필요한 시스템 라이브러리
RUN apt-get update && \
    apt-get install -y --no-install-recommends libgomp1 && \
    rm -rf /var/lib/apt/lists/*

# Python 의존성 파일 복사
COPY requirements*.txt ./

# GPU가 없는 서버이므로 CPU 전용 PyTorch 설치
RUN pip install --no-cache-dir \
    torch==2.6.0 \
    --index-url https://download.pytorch.org/whl/cpu

# Agent, RAG, OCR 라이브러리 설치
RUN pip install --no-cache-dir \
    -r requirements.txt \
    -r requirements-image-analysis.txt

# 애플리케이션 코드
COPY app ./app
COPY knowledge ./knowledge
COPY playbooks ./playbooks
COPY scripts ./scripts

EXPOSE 8000

CMD ["uvicorn", "app.main:app", \
     "--host", "0.0.0.0", \
     "--port", "8000", \
     "--workers", "1"]

