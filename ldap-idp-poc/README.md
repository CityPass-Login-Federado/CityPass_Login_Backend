# CityPass+ — Identity PoC (Group 2)

Federated login for CityPass+: **LDAP → groups → roles → JWT**. This PoC exists to prove
that chain end to end and to freeze the token format so the other seven teams can integrate
in parallel.

- **Consuming tokens?** You want [`docs/CONTRACT.md`](docs/CONTRACT.md), not this file.
- **What differs from the production design?** [`docs/PROD-DELTA.md`](docs/PROD-DELTA.md).
- **What was decided, and what is weak?** [`docs/BUILD-REPORT.md`](docs/BUILD-REPORT.md).

Out of scope on purpose: Authorization Code + PKCE, SSO session, refresh tokens, admin
console, risk scoring, Postgres, Kafka.

---

## Run it

```bash
docker compose up --build
```

That is the whole thing: OpenLDAP 2.6 comes up, the schema and overlays are already baked
into its image, the seed is applied, and the IdP starts. No manual steps.

To also exercise the human-login demo you need the PoC-only endpoint switched on:

```bash
DEBUG_TOKEN_ENABLED=true docker compose up -d --build
./scripts/demo.sh
```

`scripts/demo.sh` walks every acceptance criterion against the live stack and prints
pass/fail per item.

### `DEBUG_TOKEN_ENABLED` is off by default — this is not a bug

`POST /debug/token` accepts a raw password over HTTP. It exists only to validate the human
token format before the real OAuth flow is built. It is **not mounted at all** unless
`DEBUG_TOKEN_ENABLED=true`, so it returns **404**, not 401, when it is off. If your first
human-login attempt 404s, that is why. Set the variable (via the environment or a `.env`
copied from `.env.example`) and restart.

---

## The two `down -v` rules — they are inverses, read both

There are two named volumes and they need **opposite** treatment. Applying the wrong rule to
the wrong volume is the most likely way to break a demo.

> ### `idp-keys` — must SURVIVE
> **Never run `docker compose down -v` between issuing a token and restarting the IdP.**
>
> `-v` destroys `idp-keys`, the IdP mints a brand-new signing key on next boot, and every
> previously issued token stops validating. That is exactly the failure acceptance criterion
> 11 exists to catch, and it will happen live if someone "cleans up first".
> To cycle the IdP, use `docker compose restart idp` (or
> `docker compose up -d --force-recreate idp`, which is what the automated test does —
> it throws away the container's writable layer, so only the volume can carry the key).

> ### `ldap-data` — must BE WIPED on config change
> **Any change under `ldap/config/` requires `docker compose down -v`.**
>
> `cn=config` (schema, ACLs, overlays, the suffix) is baked into the LDAP *image* at build
> time, while the data lives in the `ldap-data` *volume*. Change a config LDIF and rebuild
> without wiping, and you get an image whose config no longer matches its data. It fails
> strangely — empty search results, obscure objectClass errors — rather than loudly.

Both volumes are destroyed together by `down -v`, so after a config change expect to re-issue
any token you were holding. To wipe only the directory and keep your signing key:

```bash
docker compose down
docker volume rm poc-ldap-idp_ldap-data
docker compose up -d --build
```

One more trap: the seed is **idempotent, not convergent**. `ldapadd -c` skips entries that
already exist, so *editing* an existing entry in `ldap/seed/` has no effect on a directory
that already has it — the change is silently ignored. Changing seed data needs the same
wipe as changing config.

---

## Credentials (local only, obviously)

| Principal | Secret |
|---|---|
| `cn=admin,dc=citypass,dc=local` | `admin-secret` |
| `cn=readonly,ou=ServiceAccounts,...` | `readonly-secret` |
| `jperez`, `mgomez`, `lrossi` | `Password123!` |
| client `grupo5` | `grupo5-secret` |
| client `grupo1` | `grupo1-secret` |

## Endpoints

| | |
|---|---|
| `GET /.well-known/jwks.json` | public JWKS |
| `GET /healthz` | includes a real search against LDAP |
| `POST /oauth/token` | `grant_type=client_credentials` — the real contract |
| `POST /debug/token` | **PoC only**, off by default |

LDAP is on `localhost:1389` (unprivileged on purpose), the IdP on `localhost:8080`.

---

## Exercising each acceptance criterion by hand

Run `DEBUG_TOKEN_ENABLED=true docker compose up -d --build` first. A helper for reading
payloads:

```bash
payload() { cut -d. -f2 <<<"$1" | tr '_-' '/+' | base64 -d 2>/dev/null | python3 -m json.tool; }
login()   { curl -s -H 'content-type: application/json' -d "$1" localhost:8080/debug/token; }
```

**1 — one command, from scratch**
```bash
docker compose down -v && docker compose up --build
```

**2 — a valid login returns a human JWT in the frozen format**
```bash
payload "$(login '{"username":"jperez","password":"Password123!","client_id":"citypass-reclamos-web"}' | jq -r .access_token)"
# sub CP-8f7d2c10, aud ["citypass-reclamos-api"], token_use human, ver 1, roles ["reclamos:agente"]
```

**3 — empty password fails**
```bash
login '{"username":"jperez","password":"","client_id":"citypass-reclamos-web"}'
# 401. LDAP treats an empty-password bind as an *unauthenticated bind* and returns success,
# so this is rejected before the bind is ever attempted.
```

**4 — nonexistent user and wrong password are indistinguishable**
```bash
login '{"username":"nosuchuser","password":"Password123!","client_id":"citypass-reclamos-web"}'
login '{"username":"jperez","password":"wrong","client_id":"citypass-reclamos-web"}'
# byte-identical bodies and statuses
```

**5 — the dual-module user gets only the requested module's roles**
```bash
payload "$(login '{"username":"mgomez","password":"Password123!","client_id":"citypass-reclamos-web"}' | jq -r .access_token)"
# roles ["reclamos:supervisor"] only -- no movilidad:consulta, though mgomez holds it
```

**5b — the client→audience registry cannot be bypassed**
```bash
login '{"username":"mgomez","password":"Password123!","client_id":"citypass-reclamos-web","audience":"citypass-movilidad-api"}'
# 400 invalid_request. Without this check, criterion 5 would prove nothing: any caller
# could simply ask for another module's audience.
```

**6 — an unmapped LDAP group produces no roles**
```bash
payload "$(login '{"username":"lrossi","password":"Password123!","client_id":"citypass-reclamos-web"}' | jq -r .access_token)"
# roles []. lrossi is in cn=app-reclamos-auditor, whose name follows the convention exactly
# but which has no entry in idp/config/idp.json. Roles are never derived from the CN.
```

**7 — the placeholder is never a role and cannot authenticate**
```bash
login '{"username":"empty-group-placeholder","password":"Password123!","client_id":"citypass-movilidad-web"}'  # 401
docker compose exec ldap ldapsearch -x -D cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local \
  -w readonly-secret -b ou=Movilidad,ou=Groups,dc=citypass,dc=local '(cn=app-movilidad-supervisor)' member -LLL
# one member, the placeholder. groupOfNames requires >= 1 member, so a truly empty group is
# a schema violation -- the placeholder exists solely to satisfy that. Do not "clean it up".
```

**8 — client_credentials, both credential styles**
```bash
curl -s -u grupo5:grupo5-secret -d grant_type=client_credentials localhost:8080/oauth/token
curl -s -d 'grant_type=client_credentials&client_id=grupo5&client_secret=grupo5-secret' localhost:8080/oauth/token
# token_use service, namespace com.citypass.reclamos, expires_in as a NUMBER
```

**9 — wrong client credentials**
```bash
curl -s -i -u grupo5:wrong -d grant_type=client_credentials localhost:8080/oauth/token
# 401, {"error":"invalid_client"}, WWW-Authenticate: Basic
```

**10 — the header `kid` is published in the JWKS**
```bash
T=$(login '{"username":"jperez","password":"Password123!","client_id":"citypass-reclamos-web"}' | jq -r .access_token)
cut -d. -f1 <<<"$T" | tr '_-' '/+' | base64 -d; echo
curl -s localhost:8080/.well-known/jwks.json | jq '.keys[].kid'
```

**11 — a token survives an IdP restart**
```bash
T=$(curl -s -u grupo5:grupo5-secret -d grant_type=client_credentials localhost:8080/oauth/token | jq -r .access_token)
docker compose up -d --force-recreate idp   # NOT down -v -- see the rules above
curl -s localhost:8080/.well-known/jwks.json | jq '.keys[].kid'   # unchanged
```

**12 — the verifier rejects bad tokens**
```bash
cd idp && npx vitest run --project unit test/unit/verifier.test.ts
```

---

## Tests

```bash
cd idp
npm install
npm test                  # unit only, no Docker needed
npm run test:integration  # needs the stack: docker compose up -d --build
npm run test:all          # both, with the 60% coverage gate
```

Integration tests run the app **in-process** against the containerised LDAP on
`localhost:1389`. Driving it through the container instead would leave the coverage reporter
blind to the code under test. The one exception is
`test/integration/restart.test.ts`, which drives the real container because the failure it
guards against — a missing volume, a wrong mount path — is a *configuration* failure that an
in-process test cannot see. It restarts a shared container, so integration files are
configured to run strictly one at a time (`fileParallelism: false`).

Current state: **116 tests, 96% line coverage** against a 60% gate.

## Layout

```
ldap/            OpenLDAP image: cn=config LDIFs, seed LDIFs, seed entrypoint
idp/src/         the IdP -- ldap.ts, keys.ts, tokens.ts, store.ts, routes/, verifier/
idp/config/      role mapping + client registry (becomes Postgres tables in production)
scripts/demo.sh  walks every acceptance criterion
docs/            CONTRACT, PROD-DELTA, BUILD-REPORT
```
