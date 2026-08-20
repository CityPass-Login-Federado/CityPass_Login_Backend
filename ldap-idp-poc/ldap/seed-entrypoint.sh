#!/bin/sh
# Applies the seed ONLINE via ldapadd. This is deliberate and load-bearing:
# slapadd bypasses overlays, so seeding offline would produce entries with no
# memberOf at all -- silently. See ldap/config/02-overlays.ldif.
set -eu

LDAP_URI="${LDAP_URI:-ldap://ldap:389}"
LDAP_ADMIN_DN="${LDAP_ADMIN_DN:-cn=admin,dc=citypass,dc=local}"
LDAP_ADMIN_PW="${LDAP_ADMIN_PW:-admin-secret}"

# Order matters: groups reference people, and refint validates member DNs.
# The `echo` between files inserts the blank line LDIF needs between entries.
for f in /ldif/seed/00-tree.ldif \
         /ldif/seed/01-serviceaccounts.ldif \
         /ldif/seed/02-people.ldif \
         /ldif/seed/03-groups.ldif; do
  cat "$f"
  echo
done > /tmp/seed.ldif

echo "[seed] applying to ${LDAP_URI}"
set +e
ldapadd -x -c -H "$LDAP_URI" -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" -f /tmp/seed.ldif
rc=$?
set -e

# Idempotency: -c continues past entries that already exist; the run then
# exits 68 (LDAP_ALREADY_EXISTS). That is a successful no-op re-run.
# Anything else is a genuine failure and must fail the container.
if [ "$rc" -eq 0 ]; then
  echo "[seed] applied"
elif [ "$rc" -eq 68 ]; then
  echo "[seed] already present, nothing to do"
else
  echo "[seed] FAILED with rc=${rc}" >&2
  exit "$rc"
fi
