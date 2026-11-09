#!/usr/bin/env bash
# Plumbing check against the fake LLM on 127.0.0.1:19501. No provider call; numbers are not scores.
cd "$(dirname "$0")"
PY=../../../../ai-server/.venv/Scripts/python.exe
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
RUN=${RUN_NAME:-dryrun}
mkdir -p "../results/$RUN"; LOG="$(cd "../results/$RUN" && pwd -W)/spans.jsonl"; rm -f "$LOG"
$PY eval_server.py --llm fake --log "$LOG" > "../results/$RUN/server.log" 2>&1 &
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 http://127.0.0.1:19501/eval/meter && break; sleep 1; done
$PY run_eval.py --label dryrun --log "$LOG" --only "${ONLY:-all}" --folder "$RUN"
PID=$(netstat -ano | grep "127.0.0.1:19501 .*LISTENING" | awk '{print $5}' | head -1); [ -n "$PID" ] && taskkill //F //T //PID "$PID" > /dev/null 2>&1
echo "port 19501 listening: $(netstat -ano | grep -c '127.0.0.1:19501 .*LISTENING')"
