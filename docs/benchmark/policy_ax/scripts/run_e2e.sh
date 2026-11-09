#!/usr/bin/env bash
# Isolated path check: throwaway MySQL, Spring from a local JAR, real FastAPI app, fake provider. Free. Never uses the shared ports.
# Usage: run_e2e.sh JAR_PATH
JAR=$1
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../../../.."; RES="$HERE/../${E2E_RESULTS:-results}"; mkdir -p "$RES"
PY="$ROOT/ai-server/.venv/Scripts/python.exe"; export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot"
MY=t2-ax-mysql DB=banking_ax_ax MYPORT=19517 SPRING=19511 API=19521 LLM=19522
export MYSQL_PWD=$(openssl rand -hex 16)
export E2E_TOKEN=$(openssl rand -hex 24) POLICY_SOURCE_TOKEN=$(openssl rand -hex 24) POLICY_SOURCE_SIGNING_SECRET=$(openssl rand -hex 32) STAFF_AGENT_GATEWAY_TOKEN=$(openssl rand -hex 24)
JWT=$(openssl rand -hex 32)
stop() { for P in $SPRING $API $LLM; do PID=$(netstat -ano | grep "127.0.0.1:$P .*LISTENING" | awk '{print $5}' | head -1); [ -n "$PID" ] && taskkill //F //T //PID "$PID" > /dev/null 2>&1; done; }
cleanup() { stop; docker rm -f $MY > /dev/null 2>&1; echo "cleanup container=$(docker ps -a --filter name=$MY -q | wc -l) listening=$(netstat -ano | grep -cE "127.0.0.1:(19511|19517|19521|19522) .*LISTENING")"; }
trap cleanup EXIT
docker rm -f $MY > /dev/null 2>&1
docker run -d --name $MY -p 127.0.0.1:$MYPORT:3306 -e MYSQL_ROOT_PASSWORD="$MYSQL_PWD" -e MYSQL_DATABASE=$DB mysql:8.0.36 > /dev/null || exit 1
for i in $(seq 1 120); do docker exec -e MYSQL_PWD $MY mysql -uroot -N -e "select 1 from information_schema.schemata where schema_name='$DB'" 2>/dev/null | grep -q 1 && break; sleep 2; done; sleep 6
"$PY" "$HERE/build_gold.py"
"$PY" "$HERE/fake_llm.py" > "$RES/fake_llm.log" 2>&1 &
(cd "$ROOT/ai-server" && RAG_WARMUP=false HF_HUB_OFFLINE=1 RAG_BACKEND=keyword APP_JWT_SECRET=$JWT APP_JWT_ISSUER=banking-ax OPENAI_API_KEY=local-fake OPENAI_BASE_URL=http://127.0.0.1:$LLM/v1 OPENAI_MODEL=gpt-4.1-mini CUSTOMER_AGENT_WORKFLOW_START_ENABLED=false CUSTOMER_AGENT_INTERNAL_TOKEN=$E2E_TOKEN ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port $API --log-level warning > "$RES/fastapi.log" 2>&1 &)
for i in $(seq 1 60); do curl -s -o /dev/null -m 2 "http://127.0.0.1:$API/health" && break; sleep 1; done
ARGS="--server.address=127.0.0.1 --server.port=$SPRING --spring.datasource.url=jdbc:mysql://127.0.0.1:$MYPORT/$DB?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=utf8 --spring.datasource.username=root --spring.datasource.password=$MYSQL_PWD --app.case-port=jpa --app.c02.review-persistence-enabled=true --app.c02.document-port=jpa --app.c02.document-provisioning-enabled=true --app.c02.file-storage-root=$(cygpath -w "$RES")/files --app.tool-gateway-token=$E2E_TOKEN --app.auth.enabled=true --app.auth.jwt-secret=$JWT --app.policy-change.source-base-url=http://127.0.0.1:$API --app.policy-change.source-token=$POLICY_SOURCE_TOKEN --app.policy-change.source-signing-secret=$POLICY_SOURCE_SIGNING_SECRET --app.staff-agent-base-url=http://127.0.0.1:$API --app.staff-agent-token=$STAFF_AGENT_GATEWAY_TOKEN --app.sms.provider=mock --app.jobs.enabled=false --logging.level.root=WARN"
HASH=$("$PY" -c "import bcrypt;print(bcrypt.hashpw(b'e2e-pass',bcrypt.gensalt(4)).decode())")
# First boot only applies Flyway, so the template accounts the seeder needs can exist before the second boot.
"$JAVA_HOME/bin/java.exe" -jar "$JAR" $ARGS > "$RES/spring1.log" 2>&1 &
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "http://127.0.0.1:$SPRING/" && break; sleep 2; done
PID=$(netstat -ano | grep "127.0.0.1:$SPRING .*LISTENING" | awk '{print $5}' | head -1); taskkill //F //T //PID "$PID" > /dev/null 2>&1; sleep 3
docker exec -i -e MYSQL_PWD $MY mysql -uroot --default-character-set=utf8mb4 $DB <<SQL 2>/dev/null
insert into branch(id,branch_code,name,address,is_active) values(1,'B1','평가 지점','isolated',true);
insert into \`user\`(login_id,password_hash,\`role\`,name,can_pre_review,branch_id) values('staff_demo','$HASH','STAFF','평가 직원 1',true,1),('e2e-reviewer2','$HASH','STAFF','평가 직원 2',true,1),('e2e-nogrant','$HASH','STAFF','권한 없는 직원',false,1);
insert into \`user\`(login_id,password_hash,\`role\`,name,phone,email) values('customer_demo','$HASH','CUSTOMER','템플릿','010-0000-0001','t@example.com');
SQL
"$JAVA_HOME/bin/java.exe" -jar "$JAR" $ARGS --app.demo-seed.c02-enabled=true > "$RES/spring2.log" 2>&1 &
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "http://127.0.0.1:$SPRING/" && break; sleep 2; done
for i in $(seq 1 60); do N=$(docker exec -e MYSQL_PWD $MY mysql -uroot -N $DB -e "select count(*) from event_log where event_type='C02_DEMO_SEEDED'" 2>/dev/null); [ "$N" -ge 6 ] 2>/dev/null && break; sleep 2; done
echo "seeded markers: $N"
"$PY" "$HERE/e2e_policy.py"
echo "driver exit $?; spring errors $(grep -ac ' ERROR ' "$RES/spring2.log")"
