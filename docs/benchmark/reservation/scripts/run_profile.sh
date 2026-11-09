#!/usr/bin/env bash
# Usage: run_profile.sh P0|P1 JAR_PATH [--smoke]. Disposable MySQL and Spring containers on their own network; removed on exit.
# P0: no resource limits. P1: both containers share one CPU, 3 GB each, the nearest local stand-in for a 1 core / 8 GB VM.
PROFILE=$1; JAR=$2; EXTRA=$3
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../../../.."
NET=t2-res-net; MY=t2-res-mysql; SP=t2-res-spring; DB=banking_ax_res
export MSYS_NO_PATHCONV=1 PYTHONUTF8=1 PYTHONIOENCODING=utf-8
export MYSQL_PWD=$(openssl rand -hex 16)
TOKEN=$(openssl rand -hex 24); JWT=$(openssl rand -hex 32)
LIMIT=""; [ "$PROFILE" = "P1" ] && LIMIT="--cpuset-cpus=0 --memory=3g"
cleanup() { docker logs $SP > "$HERE/../results/$PROFILE.spring.log" 2>&1; docker rm -f $SP $MY > /dev/null 2>&1; docker network rm $NET > /dev/null 2>&1; echo "cleanup containers=$(docker ps -a --filter name=t2-res- -q | wc -l) port19412=$(netstat -ano | grep -c ':19412 .*LISTENING')"; }
trap cleanup EXIT
docker rm -f $SP $MY > /dev/null 2>&1; docker network rm $NET > /dev/null 2>&1
mkdir -p "$HERE/../results"
docker network create $NET > /dev/null || exit 1
docker run -d --name $MY --network $NET $LIMIT -e MYSQL_ROOT_PASSWORD="$MYSQL_PWD" -e MYSQL_DATABASE=$DB mysql:8.0.36 --max-connections=300 \
  --performance-schema-consumer-events-statements-history-long=ON --performance-schema-consumer-events-transactions-current=ON --performance-schema-consumer-events-transactions-history-long=ON \
  --performance-schema-instrument='transaction=ON' --performance_schema_events_statements_history_long_size=200000 --performance_schema_events_transactions_history_long_size=200000 > /dev/null || exit 1
for i in $(seq 1 120); do docker exec -e MYSQL_PWD $MY mysql -uroot -N -e "select 1 from information_schema.schemata where schema_name='$DB'" 2>/dev/null | grep -q 1 && break; sleep 2; done
sleep 8
docker run -d --name $SP --network $NET $LIMIT -p 127.0.0.1:19412:8080 -v "$JAR:/app/app.jar:ro" eclipse-temurin:17-jre java -jar /app/app.jar --server.port=8080 \
  "--spring.datasource.url=jdbc:mysql://$MY:3306/$DB?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=utf8" --spring.datasource.username=root --spring.datasource.password=$MYSQL_PWD \
  --app.case-port=jpa --app.c02.review-persistence-enabled=true --app.c02.document-port=jpa --app.c02.document-provisioning-enabled=true --app.c02.file-storage-root=/tmp/docs --app.tool-gateway-token=$TOKEN \
  --app.auth.enabled=true --app.auth.jwt-secret=$JWT --app.staff-agent-base-url=http://127.0.0.1:1 --app.staff-agent-token=unused --app.sms.provider=mock --logging.level.root=WARN > /dev/null || exit 1
UP=0; for i in $(seq 1 90); do env -u MSYS_NO_PATHCONV curl -s -o /dev/null -m 2 "http://127.0.0.1:19412/" && { UP=1; break; }; sleep 2; done
[ $UP = 1 ] || { echo "SPRING DID NOT START"; docker logs --tail 15 $SP 2>&1 | cut -c1-200; exit 1; }
echo "$PROFILE up; flyway $(docker exec -e MYSQL_PWD $MY mysql -uroot -N $DB -e 'select max(cast(version as unsigned)) from flyway_schema_history' 2>/dev/null); cpus $(docker exec $SP nproc); javaMaxHeapMb $(docker exec $SP sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null | grep MaxHeapSize | tr -s " " | cut -d" " -f4' | awk '{print int($1/1048576)}')"
export RES_HASH=$("$ROOT/ai-server/.venv/Scripts/python.exe" -c "import bcrypt;print(bcrypt.hashpw(b'load-password',bcrypt.gensalt(4)).decode())")
env -u MSYS_NO_PATHCONV "$ROOT/ai-server/.venv/Scripts/python.exe" "$HERE/load_realistic.py" --profile "$PROFILE" $EXTRA
echo "driver exit $?; spring ERROR lines: $(docker logs $SP 2>&1 | grep -ac ' ERROR ')"
