from sentence_transformers import SentenceTransformer

# Hugging Face에서 최초 1회만 자동 다운로드
model = SentenceTransformer(
    "jhgan/ko-sroberta-multitask", # 임베딩 모델 설치
    device="cpu"
)

sentences = [
    "청약통장 해지 시 필요한 서류는 무엇인가요?",
    "청약통장을 해지할 때 제출해야 하는 증빙서류 안내",
    "오늘은 날씨가 맑아서 산책하기 좋습니다."
]

# 문장들을 벡터로 변환하기
embeddings = model.encode(
    sentences,
    normalize_embeddings=True
)

print("임베딩 크기:", embeddings.shape)
print("모델 최대 토큰 길이:", model.max_seq_length)

# 정규화된 벡터 간 코사인 유사도
print("관련 문장 유사도:", float(embeddings[0] @ embeddings[1]))
print("무관한 문장 유사도:", float(embeddings[0] @ embeddings[2]))