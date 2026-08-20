#!/usr/bin/env bash
# Walks every acceptance criterion against the running stack.
#
# Prerequisites:
#   DEBUG_TOKEN_ENABLED=true docker compose up -d --build
#
# NOTE: this script never runs `docker compose down -v`. Criterion 11 depends
# on the idp-keys volume surviving, and -v destroys it. See README.
set -uo pipefail

IDP="${IDP:-http://localhost:8080}"
LDAP_PORT="${LDAP_PORT:-1389}"
PASS=0; FAIL=0
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
head1(){ printf '\n\033[1m%s\033[0m\n' "$1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected [$3], got [$2])"; fi; }

human() { # username password client_id [audience]
  local body="{\"username\":\"$1\",\"password\":\"$2\",\"client_id\":\"$3\""
  [ -n "${4:-}" ] && body="$body,\"audience\":\"$4\""
  body="$body}"
  curl -s -w '\n%{http_code}' -H 'content-type: application/json' -d "$body" "$IDP/debug/token"
}
status() { printf '%s' "$1" | tail -1; }
json()   { printf '%s' "$1" | sed '$d'; }
claim()  { # jwt claim
  printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin).get('$2')))"
}
header() {
  printf '%s' "$1" | cut -d. -f1 | tr '_-' '/+' | base64 -d 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['$2'])"
}
tok() { json "$1" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))'; }

head1 "1. Stack is up (docker compose up brought up LDAP + seed + IdP)"
check "healthz reports ok" "$(curl -s -o /dev/null -w '%{http_code}' "$IDP/healthz")" "200"
docker inspect --format '{{.State.ExitCode}}' citypass-ldap-seed 2>/dev/null | grep -qx 0 \
  && ok "seed container completed successfully" || bad "seed container did not exit 0"

# /debug/token is off by default, and `docker compose up --force-recreate idp`
# re-reads the environment -- so running the integration suite (which recreates
# the container for criterion 11) turns it back off. Rather than fail, enable it
# and carry on.
if [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'content-type: application/json' -d '{}' "$IDP/debug/token")" = "404" ]; then
  printf '\n\033[33m/debug/token is disabled; enabling it for this demo.\033[0m\n'
  DEBUG_TOKEN_ENABLED=true docker compose up -d --force-recreate idp >/dev/null 2>&1
  for _ in $(seq 1 60); do
    [ "$(docker inspect --format '{{.State.Health.Status}}' citypass-idp 2>/dev/null)" = "healthy" ] && break
    sleep 1
  done
fi

head1 "2. A valid login returns a human JWT in the frozen format"
R=$(human jperez 'Password123!' citypass-reclamos-web); T=$(tok "$R")
check "status 200"            "$(status "$R")"      "200"
check "sub is employeeNumber" "$(claim "$T" sub)"   '"CP-8f7d2c10"'
check "aud is an array"       "$(claim "$T" aud)"   '["citypass-reclamos-api"]'
check "token_use"             "$(claim "$T" token_use)" '"human"'
check "ver"                   "$(claim "$T" ver)"   '1'
check "roles"                 "$(claim "$T" roles)" '["reclamos:agente"]'
check "preferred_username"    "$(claim "$T" preferred_username)" '"jperez"'

head1 "3. Empty password fails"
check "empty password rejected" "$(status "$(human jperez '' citypass-reclamos-web)")" "401"

head1 "4. Nonexistent user and wrong password are indistinguishable"
A=$(human nosuchuser 'Password123!' citypass-reclamos-web)
B=$(human jperez 'wrong-password' citypass-reclamos-web)
check "same status" "$(status "$A")" "$(status "$B")"
check "same body"   "$(json "$A")"   "$(json "$B")"

head1 "5. Dual-module user gets only the requested module's roles"
T=$(tok "$(human mgomez 'Password123!' citypass-reclamos-web)")
check "Reclamos token has only Reclamos roles" "$(claim "$T" roles)" '["reclamos:supervisor"]'
T=$(tok "$(human mgomez 'Password123!' citypass-movilidad-web)")
check "Movilidad token has only Movilidad roles" "$(claim "$T" roles)" '["movilidad:consulta"]'

head1 "5b. The client->audience registry cannot be bypassed"
check "foreign audience rejected" \
  "$(status "$(human mgomez 'Password123!' citypass-reclamos-web citypass-movilidad-api)")" "400"
check "registered audience accepted" \
  "$(status "$(human mgomez 'Password123!' citypass-portal citypass-movilidad-api)")" "200"

head1 "6. An unmapped LDAP group produces no roles"
T=$(tok "$(human lrossi 'Password123!' citypass-reclamos-web)")
check "lrossi authenticates" "$(claim "$T" sub)" '"CP-2c6d0f45"'
check "app-reclamos-auditor grants nothing" "$(claim "$T" roles)" '[]'

head1 "7. empty-group-placeholder is never a role and cannot authenticate"
check "placeholder cannot log in" \
  "$(status "$(human empty-group-placeholder 'Password123!' citypass-movilidad-web)")" "401"
T=$(tok "$(human mgomez 'Password123!' citypass-movilidad-web)")
printf '%s' "$(claim "$T" roles)" | grep -q 'movilidad:supervisor' \
  && bad "empty group leaked a role" || ok "movilidad:supervisor never appears"

head1 "8. client_credentials works via Basic AND via body"
BASIC=$(curl -s -u grupo5:grupo5-secret -d 'grant_type=client_credentials' "$IDP/oauth/token")
BODY=$(curl -s -d 'grant_type=client_credentials&client_id=grupo5&client_secret=grupo5-secret' "$IDP/oauth/token")
for label in "Basic:$BASIC" "body:$BODY"; do
  how="${label%%:*}"; resp="${label#*:}"
  T=$(printf '%s' "$resp" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))')
  check "$how -> token_use service" "$(claim "$T" token_use)" '"service"'
  check "$how -> namespace"         "$(claim "$T" namespace)" '"com.citypass.reclamos"'
  check "$how -> aud is an array"   "$(claim "$T" aud)"       '["citypass"]'
  printf '%s' "$resp" | grep -q '"expires_in":[0-9]' \
    && ok "$how -> expires_in is a number" || bad "$how -> expires_in is not a number"
done

head1 "9. Wrong client credentials return 401 in OAuth error shape"
E=$(curl -s -w '\n%{http_code}' -u grupo5:wrong -d 'grant_type=client_credentials' "$IDP/oauth/token")
check "status 401" "$(status "$E")" "401"
printf '%s' "$(json "$E")" | grep -q '"error":"invalid_client"' \
  && ok "error is invalid_client" || bad "error shape wrong: $(json "$E")"
check "unsupported_grant_type" \
  "$(curl -s -u grupo5:grupo5-secret -d 'grant_type=password' "$IDP/oauth/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["error"])')" \
  "unsupported_grant_type"

head1 "10. The token header kid matches a key published in the JWKS"
T=$(tok "$(human jperez 'Password123!' citypass-reclamos-web)")
KID=$(header "$T" kid)
curl -s "$IDP/.well-known/jwks.json" | grep -q "$KID" \
  && ok "kid $KID is in the JWKS" || bad "kid $KID missing from JWKS"
curl -s "$IDP/.well-known/jwks.json" | grep -qE '"d"|"p"|"q"' \
  && bad "JWKS leaks private key material" || ok "JWKS publishes only public components"

head1 "11. A token issued before restart still validates after it"
T=$(tok "$(human jperez 'Password123!' citypass-reclamos-web)")
BEFORE=$(curl -s "$IDP/.well-known/jwks.json")
echo "  recreating idp container (NOT 'down -v' -- that would wipe the key volume)..."
# --force-recreate rather than `restart`: restarting reuses the same writable
# layer, so a key stored outside the volume would survive and prove nothing.
# DEBUG_TOKEN_ENABLED is re-passed because recreating re-reads the environment.
DEBUG_TOKEN_ENABLED=true docker compose up -d --force-recreate idp >/dev/null 2>&1
for _ in $(seq 1 60); do
  [ "$(docker inspect --format '{{.State.Health.Status}}' citypass-idp 2>/dev/null)" = "healthy" ] && break
  sleep 1
done
AFTER=$(curl -s "$IDP/.well-known/jwks.json")
check "JWKS unchanged across restart" "$AFTER" "$BEFORE"
curl -s "$IDP/.well-known/jwks.json" | grep -q "$(header "$T" kid)" \
  && ok "the pre-restart token's kid is still published" || bad "kid vanished across restart"

head1 "12. Verifier rejections"
echo "  (covered by the automated suite: cd idp && npm test -- verifier)"
( cd idp && npx vitest run --project unit test/unit/verifier.test.ts >/dev/null 2>&1 ) \
  && ok "verifier negative tests pass" || bad "verifier negative tests failed"

head1 "R18. The directory serves exactly one naming context"
NC=$(docker compose exec -T ldap ldapsearch -x -H ldap://localhost:389 -b '' -s base namingContexts -LLL 2>/dev/null | grep '^namingContexts:' | sed 's/namingContexts: //')
check "namingContexts" "$NC" "dc=citypass,dc=local"

printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
