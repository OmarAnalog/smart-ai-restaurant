#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
pg="restaurant-integration-$$-pg"
cleanup() { docker rm -fv "$pg" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$pg" -e POSTGRES_DB=restaurant -e POSTGRES_USER=restaurant \
  -e POSTGRES_PASSWORD=test-only -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
ready=false
for attempt in {1..45}; do
  if docker exec "$pg" pg_isready -U restaurant -d restaurant >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
if [ "$ready" != true ]; then echo "Integration database did not become ready" >&2; exit 1; fi
pg_port="$(docker port "$pg" 5432 | sed -n 's/.*://p')"
export DATABASE_INTEGRATION=true
export TEST_DATABASE_URL="jdbc:postgresql://localhost:$pg_port/restaurant"
maven_args=()
if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then maven_args+=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL"); fi
mvn "${maven_args[@]}" -f backend/pom.xml test
