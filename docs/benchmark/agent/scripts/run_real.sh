#!/usr/bin/env bash
# Real provider run. Refuses to start unless the owner approved it and set BANKING_AX_AGENT_EVAL_REAL_LLM=true.
# Uses ai-server/.env for the key; the key is never printed. Port 19501 only.
cd "$(dirname "$0")"
# Checked first, so an earlier run can never be overwritten, approved or not.
RUN=${RUN_NAME:?Set RUN_NAME so an earlier run is not overwritten}
[ ! -e "../results/$RUN" ] || { echo "results/$RUN already exists; choose another RUN_NAME."; exit 3; }
[ "$BANKING_AX_AGENT_EVAL_REAL_LLM" = "true" ] || { echo "Not approved: set BANKING_AX_AGENT_EVAL_REAL_LLM=true after budget approval."; exit 2; }
PY=../../../../ai-server/.venv/Scripts/python.exe
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
MAX_CALLS=${MAX_CALLS:-150}; BUDGET_USD=${BUDGET_USD:-1.00}
mkdir -p "../results/$RUN"; LOG="$(cd "../results/$RUN" && pwd -W)/spans.jsonl"
$PY eval_server.py --llm real --max-calls "$MAX_CALLS" --budget-usd "$BUDGET_USD" --log "$LOG" > "../results/$RUN/server.log" 2>&1 &
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 http://127.0.0.1:19501/eval/meter && break; sleep 1; done
$PY run_eval.py --label real --log "$LOG" --only "${ONLY:-all}" --folder "$RUN"
PID=$(netstat -ano | grep "127.0.0.1:19501 .*LISTENING" | awk '{print $5}' | head -1); [ -n "$PID" ] && taskkill //F //T //PID "$PID" > /dev/null 2>&1
echo "port 19501 listening: $(netstat -ano | grep -c '127.0.0.1:19501 .*LISTENING')"
