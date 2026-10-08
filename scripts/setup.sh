#!/usr/bin/env bash
# Creates one realm per tenant database (placement via the X-Keycloak-Datasource header), a public client for
# password logins, a confidential client for client-credentials tokens and USERS_PER_REALM users per realm.
# Then verifies where the rows landed and that a login works on every realm.
set -euo pipefail

KC_URL=${KC_URL:-http://localhost:8080}
HEALTH_URL=${HEALTH_URL:-http://localhost:9000}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASS=${ADMIN_PASS:-admin}
REALMS=${REALMS:-"alpha beta gamma"}
USERS_PER_REALM=${USERS_PER_REALM:-100}
COMPOSE_FILE="$(cd "$(dirname "$0")/.." && pwd)/docker/compose.yaml"

echo "Waiting for Keycloak at $HEALTH_URL/health/ready ..."
for _ in $(seq 1 120); do
  if curl -sf "$HEALTH_URL/health/ready" >/dev/null; then break; fi
  sleep 3
done
curl -sf "$HEALTH_URL/health/ready" >/dev/null || { echo "Keycloak did not become ready"; exit 1; }

admin_token() {
  curl -sf -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])'
}

TOKEN=$(admin_token)

for realm in $REALMS; do
  if curl -sf -o /dev/null -H "Authorization: Bearer $TOKEN" "$KC_URL/admin/realms/$realm"; then
    echo "realm '$realm' already exists, skipping"
    continue
  fi
  tmp=$(mktemp)
  python3 - "$realm" "$USERS_PER_REALM" > "$tmp" <<'PY'
import json, sys
realm, n = sys.argv[1], int(sys.argv[2])
users = [{
    "username": f"user-{i}", "enabled": True, "email": f"user-{i}@{realm}.example",
    "firstName": "User", "lastName": str(i),
    "credentials": [{"type": "password", "value": "password", "temporary": False}],
} for i in range(1, n + 1)]
rep = {
    "realm": realm, "enabled": True, "sslRequired": "none", "eventsEnabled": True,
    "accessTokenLifespan": 300,
    "clients": [
        {"clientId": "loadtest", "publicClient": True, "directAccessGrantsEnabled": True,
         "standardFlowEnabled": True, "redirectUris": ["*"]},
        {"clientId": "loadtest-svc", "publicClient": False, "secret": "loadtest-secret",
         "serviceAccountsEnabled": True, "directAccessGrantsEnabled": False, "standardFlowEnabled": False},
    ],
    "users": users,
}
json.dump(rep, sys.stdout)
PY
  echo "creating realm '$realm' on datasource '$realm' with $USERS_PER_REALM users ..."
  TOKEN=$(admin_token)
  curl -sf -o /dev/null -w "  HTTP %{http_code}\n" -X POST "$KC_URL/admin/realms" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "X-Keycloak-Datasource: $realm" --data @"$tmp"
  rm -f "$tmp"
done

echo
echo "--- router status (GET /realms/master/realm-router)"
TOKEN=$(admin_token)
curl -sf -H "Authorization: Bearer $TOKEN" "$KC_URL/realms/master/realm-router" | python3 -m json.tool

echo
echo "--- rows per database (via docker compose exec postgres)"
for db in keycloak kc_alpha kc_beta kc_gamma; do
  docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U keycloak -d "$db" -At -F ' | ' -c \
    "select '$db', coalesce((select string_agg(name, ',') from realm), '-') as realms, (select count(*) from user_entity) as users, (select count(*) from client) as clients, (select count(*) from event_entity) as events"
done

echo
echo "--- login smoke test (password grant, user-1)"
for realm in $REALMS; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KC_URL/realms/$realm/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=loadtest -d username=user-1 -d password=password -d scope=openid)
  echo "  $realm: HTTP $code"
done
