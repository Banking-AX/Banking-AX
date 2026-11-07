#!/usr/bin/env bash
# Runs the working-tree code beside the existing dev servers (18081/9001/5175), which stay untouched.
#   bash scripts/policy-parallel-dev.sh          start 18083/9002/5176 until Ctrl+C
#   bash scripts/policy-parallel-dev.sh --check  start, check wiring without logging in, stop
# It uses the shared dev database with the real backend/.env, except that SMS is forced to mock.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AI=9002 SPRING=18083 UI=5176
LOGS="${POLICY_PARALLEL_LOGS:-$ROOT/backend/target}"
: "${JAVA_HOME:=/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"; export JAVA_HOME
MVN="${MVN:-/c/Temp/apache-maven-3.9.9/bin/mvn.cmd}"

for port in $AI $SPRING $UI; do
  if netstat -ano | grep -q ":$port .*LISTENING"; then echo "port $port is in use"; exit 1; fi
done

stop() {
  for port in $AI $SPRING $UI; do
    pid=$(netstat -ano | grep "127.0.0.1:$port " | grep LISTENING | awk '{print $5}' | head -1)
    [ -n "$pid" ] && taskkill //F //T //PID "$pid" > /dev/null 2>&1
  done
}
trap stop EXIT

# The Windows-level OPENAI_API_KEY is invalid and would beat ai-server/.env, so this process drops it.
# uvicorn sometimes exits at startup here with WinError 10014, so a failed start is retried.
for _ in 1 2 3; do
  (cd "$ROOT/ai-server" && env -u OPENAI_API_KEY RAG_WARMUP=false ./.venv/Scripts/python.exe -m uvicorn app.main:app \
    --host 127.0.0.1 --port $AI --log-level warning > "$LOGS/parallel-fastapi.log" 2>&1 &)
  for _ in $(seq 1 25); do curl -s -o /dev/null "http://127.0.0.1:$AI/health" && break 2; sleep 1; done
done
curl -s -o /dev/null "http://127.0.0.1:$AI/health" || { echo "FastAPI did not start: see $LOGS/parallel-fastapi.log"; exit 1; }

(
  cd "$ROOT/backend"
  while IFS='=' read -r key value; do
    case "$key" in ''|\#*) continue ;; esac
    export "$key=$value"
  done < <(tr -d '\r' < .env)
  export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=$SPRING APP_SMS_PROVIDER=mock APP_DEMO_SEED_C02_ENABLED=false
  export APP_POLICY_SOURCE_BASE_URL="http://127.0.0.1:$AI" APP_STAFF_AGENT_BASE_URL="http://127.0.0.1:$AI" APP_CUSTOMER_AGENT_BASE_URL="http://127.0.0.1:$AI"
  "$MVN" -o -q compile spring-boot:run > "$LOGS/parallel-spring.log" 2>&1 &
)
for _ in $(seq 1 120); do grep -q "Started BankingAxApplication" "$LOGS/parallel-spring.log" 2>/dev/null && break; sleep 1; done
grep -q "Started BankingAxApplication" "$LOGS/parallel-spring.log" || { echo "Spring did not start: see $LOGS/parallel-spring.log"; exit 1; }

(cd "$ROOT/frontend" && VITE_PROXY_SPRING=http://127.0.0.1:$SPRING VITE_PROXY_AI=http://127.0.0.1:$AI \
  npx vite --host 127.0.0.1 --port $UI --strictPort > "$LOGS/parallel-vite.log" 2>&1 &)
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$UI/" && break; sleep 1; done

if [ "${1:-}" = "--check" ]; then
  value() { sed -n "s/^$2=//p" "$ROOT/$1" | tr -d '\r' | tail -1; }
  code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
  echo "anonymous staff API (expect 401): $(code "http://127.0.0.1:$SPRING/api/v1/staff/policy-changes?requestId=check")"
  echo "feed with backend token (expect 200): $(code -H "X-Policy-Source-Token: $(value backend/.env POLICY_SOURCE_TOKEN)" "http://127.0.0.1:$AI/mock-bank/v1/policy-changes")"
  echo "feed without token (expect 403): $(code "http://127.0.0.1:$AI/mock-bank/v1/policy-changes")"
  echo "agent with backend token (expect 422): $(code -X POST -H 'Content-Type: application/json' -H "X-Staff-Agent-Token: $(value backend/.env APP_STAFF_AGENT_GATEWAY_TOKEN)" -d '{}' "http://127.0.0.1:$AI/api/v1/staff-agent/policy-impact")"
  echo "ui through parallel proxy (expect 401): $(code "http://127.0.0.1:$UI/api/v1/staff/policy-changes?requestId=check")"
  exit 0
fi
echo "ready: http://127.0.0.1:$UI (Ctrl+C stops only these three processes)"
while :; do sleep 3600; done
