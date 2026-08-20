# Build report

What actually happened while building this, as opposed to what the plan said would happen.
Read this before the other three documents.

**Result:** all 12 acceptance criteria plus 5b are demonstrable and automated.
122 tests, 96.45% line coverage against a 60% gate. `docker compose up --build` from a wiped
state works with no manual steps — verified by doing exactly that, `down -v` included, and
re-running `scripts/demo.sh` (37/37).

---

## 1. Decisions the plan did not cover

Erring heavily toward inclusion, as asked.

### Directory / LDAP

**1.1 — `cn=config` is three LDIF files, not two.** The plan specified `00-base.ldif` +
`01-overlays.ldif`. That does not survive contact: `olcSuffix: dc=citypass,dc=local` cannot
be parsed until `dcObject` is loaded, and `employeeNumber` needs `inetOrgPerson`, so the
stock schema LDIFs must be spliced *between* the global section and the database section.
Result: `00-base.ldif` (global + modules + `cn=schema` parent), `01-database.ldif`
(frontend + mdb + ACLs), `02-overlays.ldif`, with `/etc/ldap/schema/{core,cosine,inetorgperson}.ldif`
concatenated in between by the Dockerfile.

**1.2 — every LDIF concatenation inserts a blank line.** Found the hard way: the build failed
with `str2entry: entry -1 has multiple DNs "cn=core,..." and "cn=cosine,..."`. None of the
stock schema files end with a blank line, and LDIF requires one between entries, so a plain
`cat` fuses the last entry of one file onto the first of the next. Both the Dockerfile and
`seed-entrypoint.sh` now loop with an `echo` between files. This is commented at both sites
because it looks like decoration and is not.

**1.3 — `olcRefintNothing` points at the placeholder.** `refint` takes a DN to substitute
when an operation would leave a group with no members. Pointing it at
`cn=empty-group-placeholder` means the "groups can never be truly empty" invariant is
enforced by the directory rather than by convention. The plan did not mention this option;
it happens to fit the placeholder design exactly.

**1.4 — `cn=idp-admin` has no `userPassword` at all.** The plan said "reserved, unused".
Seeding it with a password would create a live credential nobody uses, which is worse than
useless. With no password it cannot bind. There is a test asserting this.

**1.5 — ACL shape.** `userPassword` → `by self write by anonymous auth by * none`; everything
else → `by dn.exact="cn=readonly,..." read by self read by * none`. The frontend gets a
narrow `to dn.base="" by * read` so the healthcheck and the R18 regression test can read the
rootDSE anonymously.

**1.6 — LDAP is published on host port 1389, not 389.** 389 is privileged and would fail on
a rootless daemon. Inside the compose network it is still 389.

### IdP

**1.7 — no build step; `tsx` runs the TypeScript directly.** A `tsc → dist` stage buys
nothing for a PoC and adds a way for the image to ship stale JavaScript. `tsc --noEmit` still
runs as a typecheck. **Consequence worth knowing:** `package.json` `exports` points at raw
`.ts`, so a team importing the verifier needs `tsx` or their own build. Noted in
`docs/CONTRACT.md` §7.

**1.8 — no separate `gen-key` script or entrypoint hook.** The plan had one. `ensureKey()`
inside `loadKeystore()` covers it: generate only if the directory has no `*.pem`. Fewer
moving parts, and the "generate exactly once" property is unit-tested rather than living in
shell.

**1.9 — TTL is validated at boot, not just documented.** `TOKEN_TTL_SECONDS` outside 600–900
throws at startup. The spec fixes the window at 10–15 minutes; making that a runtime
assertion stops a stray env var quietly widening the blast radius of a leaked token.

**1.10 — failure taxonomy on `/debug/token`.** The plan did not specify status codes per
failure. Chosen:
- missing `client_id` → 400 `invalid_request` (structural, reveals nothing about users)
- unknown `client_id` → 401 `invalid_client`
- **missing/empty/wrong username or password, unknown user, ambiguous user, directory down →
  401 `invalid_grant`, one byte-identical body for all of them.** Including empty password:
  the "reject before binding" rule is about not reaching LDAP, not about telling the caller
  why. A test asserts the responses are string-equal.
- service-only client asking for a human token → 400 `invalid_request`. Debatable — 403 is
  arguable. 400 won because the request is well-formed but semantically invalid for that
  client, and no authentication was attempted.
- client with more than one allowed audience and no explicit `audience` → 400. Guessing on
  the caller's behalf is how audience confusion starts.

**1.11 — `jti` is a UUID, not the 8-hex string in the spec examples.** `"a1b2c3d4"` has ~4
billion values; at a few thousand tokens a day collisions are a matter of when. `jti` is
declared opaque in the contract, and the `svc-` prefix on service tokens is preserved because
that *is* visible in the spec. Flagging in case the short form was deliberate.

**1.12 — `/healthz` returns the active `kid`.** Harmless (it is in the public JWKS anyway) and
makes "did the key change across a restart?" a one-line check during a demo.

**1.13 — `express.json()` is mounted globally**, so `/oauth/token` also accepts a JSON body.
RFC 6749 says form-encoded. Harmless and convenient; noted as R27 below.

### Testing

**1.14 — integration tests run the app in-process against the containerised LDAP.** Driving
the app through its own container would make the coverage reporter blind to every line under
test and the 60% gate would be measuring nothing. Only `restart.test.ts` drives the real
container, because the failure *it* guards against is configuration, not code.

**1.15 — the restart test uses `/oauth/token`, not `/debug/token`.** `/debug/token` is off by
default, so using it would have made criterion 11 depend on a `.env` file existing.

**1.16 — the restart test uses `jose` directly rather than the project verifier.** So that a
red result can only mean key persistence broke, never that the verifier changed. The verifier
has its own 30 tests.

**1.17 — `docker compose up -d --force-recreate idp`, not `docker compose restart idp`.**
This one is a genuine correction to the approved plan, made late. `restart` reuses the same
container and therefore the same writable layer: a key written *outside* `/keys` would
survive a `restart` and the test would pass on a broken configuration — precisely the bug
criterion 11 exists to catch. `--force-recreate` discards the writable layer, so only the
`idp-keys` volume can carry the key across. Verified independently:
`docker run --rm -v poc-ldap-idp_idp-keys:/k debian ls -la /k` shows the `.pem` at mode
`0600`, and there is no stray `.pem` anywhere in the container filesystem.
`scripts/demo.sh` and the README were updated to match.

**1.18 — `scripts/demo.sh` sits at the repo root**, not `idp/scripts/`. It drives the whole
stack, not the IdP package.

### From the subagent tracks

**1.19 (secrets)** — `verifySecret` derives using the **stored** `N`/`r`/`p` rather than
today's constants, so raising the cost factor later does not invalidate existing hashes.
Base64 fields are re-encoded and compared during parsing, because Node's decoder silently
drops invalid characters instead of throwing — without that, `$***$` would decode to an empty
buffer and count as well-formed.

**1.20 (oauth)** — a non-`Basic` `Authorization` header (e.g. a stray `Bearer`) is ignored
rather than counted as a second authentication mechanism, so it does not spuriously trigger
the both-methods 400. Body credentials count as "supplied" if *either* `client_id` or
`client_secret` is present, so `client_id` + empty secret alongside a Basic header still
trips the 400 rather than being silently ignored.

**1.21 (verifier)** — `requiredClaims: ['exp','iat','sub','jti']` is passed to `jwtVerify`.
This is load-bearing: **jose skips the expiry check entirely when `exp` is absent**, so
without it a token minted with no `exp` would verify forever. There is a test for it.

**1.22 (verifier)** — two error codes beyond the plan's list: `key` (kid not in JWKS, or JWKS
fetch/parse failure — operationally very different from a forged signature) and `malformed`.
`ver` must be exactly `1`; a future `2` is rejected until the verifier is updated, which is
the safe direction.

---

## 2. What the plan asserted that turned out to be wrong

**2.1 — "scrypt throws without `maxmem` at N=16384".** Stated in the Track B brief, and it is
false. 128·N·r = 16 MiB, which fits under Node's 32 MiB default. `maxmem: 64 MiB` is still
set, but for a real reason — headroom to *verify* hashes whose stored parameters are larger
than today's — and the code comment says that rather than repeating the wrong claim.

**2.2 — "jose normalises a single-string `aud` to a string, so you must check
`Array.isArray`".** The conclusion is right, the reason is wrong for jose v6: `payload.aud`
is exactly what was on the wire, untouched. The check is still mandatory, because jose
happily *accepts* a bare-string `aud` — it is a contract violation, not a normalisation
artifact. Corrected in `docs/CONTRACT.md`.

**2.3 — the two-file `cn=config` layout.** See 1.1.

**2.4 — a plain `cat` of LDIF files works.** See 1.2. Cost about ten minutes.

**2.5 — `docker compose restart` is sufficient for criterion 11.** See 1.17. This was in the
approved plan and in my own Stage-0 addendum, and it was too weak.

**2.6 — arm64 could be verified by building.** Recorded during Stage 0: no `qemu-aarch64`
binfmt handler on this machine and no `buildx` on Docker 29.7.2, so arm64 was verified via
package metadata (`slapd:arm64` = `2.6.10+dfsg-1`, identical to amd64) rather than by
execution. For an apt-only Dockerfile that is the claim that matters, but **it has not been
run on ARM.**

**2.7 — R9's replacement text was written from release notes; it is now verified.** `ldd` on
the built image shows `libssl.so.3` + `libcrypto.so.3` and no GnuTLS. The claim held.

---

## 3. New risks found during implementation (continuing from R18)

**R19 — `docker compose down -v` destroys both volumes, but the two rules are opposites.**
The README documents that `ldap-data` must be wiped on config change and `idp-keys` must
survive. Docker gives no way to honour both with one command. To wipe only the directory:
`docker compose down && docker volume rm poc-ldap-idp_ldap-data && docker compose up -d`.
The README states the rules; it does not state this escape hatch. It should.

**R20 — the seed is idempotent, not convergent.** `ldapadd -c` skips entries that already
exist and the entrypoint treats rc 68 as success. That makes re-running safe, but it also
means **editing an existing entry in a seed LDIF has no effect on an existing volume** — the
change is silently ignored. Changing seed *data* requires `down -v`, same as changing config,
and nothing warns you. This is the most likely "why isn't my change showing up" trap for
whoever picks this up next.

**R21 — no rate limiting anywhere.** Neither `/debug/token` nor `/oauth/token` has any
throttling, and there is no LDAP account lockout configured. On the compose network that is
fine. On the VPS it is an open password-guessing oracle against real accounts. This needs to
be solved before deployment, and it is not a code change in this repo alone — LDAP-side
lockout (`ppolicy` overlay) is the more robust half.

**R22 — the `readonly` account can read every attribute of every entry except passwords.**
Sufficient and simple for the PoC. In production it should be scoped to
`ou=People` + the specific attributes the IdP needs, so a compromised readonly bind does not
dump the whole directory.

**R23 — the IdP container runs as root.** `node:22-slim` defaults to root and nothing drops
privileges. The key file is `0600` but owned by root. Add `USER node` and `chown` the volume
before deploying.

**R24 — tokens cannot be revoked.** No `jti` replay cache, no revocation list, no `nbf`. A
leaked token is valid until `exp`. The 15-minute TTL is the entire mitigation. That is a
deliberate consequence of stateless JWTs and matches the design, but it should be a conscious
decision rather than a discovery.

**R25 — public clients have no authentication at all.** `citypass-portal` may request either
audience and has `secretHash: null`. Anyone with valid *user* credentials can obtain a portal
token. This is inherent to `/debug/token` and is one of the things PKCE exists to fix; it
disappears with the debug endpoint.

**R26 — `/healthz` is unauthenticated and performs a real LDAP search per call.** With the
compose healthcheck at a 3-second interval, that is a bind-and-search every 3 seconds
forever. The long-lived readonly connection keeps it cheap, but an unauthenticated endpoint
that causes directory load is a small DoS amplifier on a public VPS.

**R27 — `/oauth/token` accepts JSON as well as form-encoded bodies.** Because
`express.json()` is mounted globally. Not harmful, but it is outside RFC 6749 and the
contract document promises form-encoded. Worth tightening if strict conformance matters for
grading.

**R28 — recreating a container re-reads the environment, silently.**
`docker compose up -d --force-recreate idp` picks up whatever is in the *current* shell, not
what was set when the stack first came up. Because the criterion-11 test recreates the IdP
container, **running the integration suite turns `/debug/token` back off**, and a demo run
afterwards fails confusingly. Found by hitting it. `scripts/demo.sh` now detects the 404 and
re-enables the endpoint itself rather than failing; the durable fix is a committed `.env`,
which conflicts with keeping secrets out of the repo. Anything else that is passed by
environment rather than by file has the same exposure.

---

## 4. What is weakest

Blunt, as requested.

**4.1 — There is no authentication logging. At all.** This is the single biggest gap and it
is not a small one for an *identity provider*. `LdaptsService.authenticate` returns `null`
for a wrong password, an unknown user, an ambiguous match, a missing `employeeNumber`, a
directory outage and an internal exception — identically, and silently. The response
behaviour is correct (no enumeration oracle). The operational behaviour is not: an LDAP
outage looks exactly like everyone mistyping their password, and there is no audit trail of
who logged in. The `catch` block in `src/ldap.ts` carries a comment saying so. **Fix this
before anything real depends on it.** It was left out because it was not in the approved
scope, not because it is optional.

**4.2 — 96% coverage is a flattering number.** What is covered is the happy paths and the
security-relevant rejections, which is the right prioritisation, but the uncovered remainder
is concentrated in error handling: the `Buffer` branch of the LDAP attribute normaliser, the
`decodeURIComponent` fallback in Basic parsing, and parts of the verifier's error mapping
(its branch coverage is 77.6%, the lowest in the repo). Coverage percentage is not evidence
that failure modes work.

**4.3 — the LDAP `catch` swallows programming errors.** Any exception in the search path —
including a genuine bug — becomes "authentication failed". Combined with 4.1, a real defect
in that function could sit undetected indefinitely while looking like user error.

**4.4 — R10 (config/data drift) is documented, not enforced.** The README says any change
under `ldap/config/` requires `down -v`. Nothing checks it. A stale volume against a rebuilt
image fails strangely rather than loudly, which is exactly the case a rule in prose is worst
at preventing. A boot-time assertion comparing a hash of the config LDIFs against a marker
entry in the directory would close this; it is not built.

**4.5 — `scripts/demo.sh` is shell parsing JWTs with `base64 -d` and `python3`.** It works and
it is 37/37 green, but base64url padding handling is fragile and the failure mode is a
confusing diff rather than a clear error. The vitest suite is the real evidence; the demo
script is a presentation aid. Do not treat a green demo as a substitute for `npm run test:all`.

**4.6 — the seed is small enough to flatter the role mapping.** Three users, five groups, two
audiences. Nothing exercises a user in many groups, deeply nested OUs, DN escaping in group
names, or non-ASCII. The naming convention forbids accents, but nothing enforces that either.

**4.7 — nothing has ever run on ARM.** See 2.6. The package exists for arm64; the image has
not been built or run there. This is the assertion in this repo most likely to be wrong.

**4.8 — the verifier is TypeScript and the consumers may not be.** If any of the seven teams
is on Java or Python, the shipped module is inert for them. `docs/CONTRACT.md` was written to
be implementable from scratch on any stack for exactly this reason, but **nobody has yet
implemented it from the document alone**, which is the only real test of whether the document
is sufficient.

---

## 5. What you should verify yourself rather than take on my word

1. **Run `docker compose down -v && DEBUG_TOKEN_ENABLED=true docker compose up -d --build && ./scripts/demo.sh`
   on your own machine.** Everything here was verified on Linux/amd64, Docker 29.7.2,
   Node 26 locally / Node 22 in the image. That is one environment.
2. **Build the LDAP image on ARM.** The one claim in this repo verified only from package
   metadata (2.6). If it fails, Stage 0's fallback chain is `symas/openldap:2.6` first,
   Alpine second.
3. **Have one of the other six teams implement the verifier from `docs/CONTRACT.md` without
   reading the TypeScript.** If they cannot, the contract document has a gap, and that is the
   actual deliverable of this PoC. This is the highest-value thing on this list.
4. **Decide whether `jti` as a UUID is acceptable** (1.11), and whether the 400-vs-403 choice
   for a service client requesting a human token matches what your ADRs say (1.10).
5. **Confirm the client list in `idp/config/idp.json` matches reality.** `grupo5` and
   `grupo1` with namespaces `com.citypass.reclamos` / `com.citypass.eventbus` are placeholders
   I invented from the brief. The real client ids, secrets and namespaces have to come from
   the other teams.
6. **Read `src/routes/oauth.ts` and `src/verifier/index.ts` yourself.** Those two files were
   written by subagents against a specification I wrote. I reviewed the reported behaviour and
   the tests pass, but they are the two most security-sensitive files in the repo and
   deserve a human read.
7. **Check the coverage exclusions in `idp/vitest.config.ts`.** `src/server.ts` is excluded as
   a bootstrap. If your course's 60% requirement is measured differently, that exclusion is
   the first thing a marker will look at.
