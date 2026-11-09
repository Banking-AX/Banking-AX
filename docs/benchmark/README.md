# AX 측정 실험

`scripts/benchmark/c02_precheck_benchmark.py`는 합성 메타데이터 30건으로 C-02 사전점검과 직원 결과서 안전장치를 측정한다. 네트워크, 기존 서버, 공유 DB, 실제 OCR 및 실제 LLM을 호출하지 않는다.

```powershell
ai-server/.venv/Scripts/python.exe scripts/benchmark/c02_precheck_benchmark.py
```

결과는 `docs/benchmark/results/c02_offline_benchmark.json`에 생성한다. 이 파일은 로컬 실행 산출물이므로 Git에 추가하지 않는다.

실제 LLM 성능 측정은 `BANKING_AX_ENABLE_REAL_LLM=true`와 별도 비용 승인, 호출 상한, 비밀키 주입이 모두 갖춰진 환경에서만 별도 스크립트/결과로 시행한다. 단순히 `OPENAI_API_KEY`가 존재하는 것은 실행 권한이 아니다.
