#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
pg="restaurant-integration-$$-pg"
broker="restaurant-integration-$$-rabbit"
cleanup() { docker rm -fv "$pg" "$broker" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$pg" -e POSTGRES_DB=restaurant -e POSTGRES_USER=restaurant \
  -e POSTGRES_PASSWORD=test-only -p 127.0.0.1::5432 pgvector/pgvector:pg16 >/dev/null
ready=false
for attempt in {1..45}; do
  if docker exec "$pg" pg_isready -U restaurant -d restaurant >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
if [ "$ready" != true ]; then echo "Integration database did not become ready" >&2; exit 1; fi
pg_port="$(docker port "$pg" 5432 | sed -n 's/.*://p')"
docker run -d --name "$broker" -e RABBITMQ_DEFAULT_USER=restaurant -e RABBITMQ_DEFAULT_PASS=restaurant \
  -p 127.0.0.1::5672 rabbitmq:4-management >/dev/null
ready=false
for attempt in {1..60}; do
  if docker exec --user rabbitmq "$broker" rabbitmq-diagnostics -q ping >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [ "$ready" != true ]; then echo "Integration broker did not become ready" >&2; docker logs --tail 60 "$broker" >&2; exit 1; fi
export TEST_RABBITMQ_PORT="$(docker port "$broker" 5672 | sed -n 's/.*://p')"
export DATABASE_INTEGRATION=true
export TEST_DATABASE_URL="jdbc:postgresql://localhost:$pg_port/restaurant"
maven_args=()
if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then maven_args+=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL"); fi
mvn "${maven_args[@]}" -f backend/pom.xml test
