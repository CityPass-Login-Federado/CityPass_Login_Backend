# PoC ↔ production divergences

Every place this PoC deliberately does something the production design does not, with the
exact extension point. If you are migrating one of these, the file and line below is where
the work starts and — by design — where it ends.

Line numbers are accurate as of the initial build; the `EXTENSION POINT (prod):` comments are
the durable markers. `grep -rn "EXTENSION POINT" idp/src/` finds all of them.

---

## 1. Role mapping and client registry: file → Postgres

| | |
|---|---|
| **PoC** | `idp/config/idp.json`, read once at boot |
| **Production** | two Postgres tables (`role_mapping`, `oauth_client`) |
| **Extension point** | `idp/src/store.ts:9` (the `ConfigStore` interface) and `idp/src/app.ts:22` (the single construction site) |

`ConfigStore` has two methods and no knowledge of where its data comes from:

```ts
getClient(clientId: string): ClientRecord | undefined;
getRolesForGroups(groupDns: string[], audience: string): string[];
```

`FileConfigStore` is the only implementation. Migrating means writing `PgConfigStore` and
changing `idp/src/app.ts:22` from `FileConfigStore.fromFile(config.configFile)` to the new
one. Nothing else in the codebase reads the mapping — routes only ever see the interface.

Two behaviours must be preserved by any implementation, because tests depend on them and
because they are security properties, not conveniences:

- **DN matching is normalised** (`normalizeDn`, `idp/src/store.ts`): case-folded, RDN spacing
  collapsed. A directory is free to return `CN=X, OU=Y` where the mapping says
  `cn=x,ou=y`, and a naive string compare would silently drop the role. In Postgres, either
  normalise on write and on read, or index a normalised column.
- **A group with no mapping row grants nothing, and roles are never parsed out of the CN.**
  This is what stops someone creating `cn=app-reclamos-admin` in LDAP and granting
  themselves privileges.

---

## 2. LDAP transport: plaintext → TLS

| | |
|---|---|
| **PoC** | `ldap://ldap:389` on the internal compose network. Not exposed off-host. |
| **Production** | `ldaps://` or StartTLS. **Mandatory on the VPS** — the readonly bind password and every user password cross the wire in the clear otherwise. |
| **Extension point** | `idp/src/ldap.ts:48` (`newClient()`) — the single place every LDAP connection is constructed, both the long-lived search client and the ephemeral bind clients |

```ts
return new Client({
  url: this.config.ldapUrl,          // becomes ldaps://...
  connectTimeout: ..., timeout: ...,
  // add: tlsOptions: { ca: [readFileSync(config.ldapTlsCa)] }
});
```

`AppConfig` gains `ldapTlsCa`; `docker-compose.yml` gains the CA mount. That is the whole change
on the client side.

### ⚠ Debian 13 moved OpenLDAP from GnuTLS to OpenSSL — verified, not assumed

The LDAP image is `debian:trixie-slim` (slapd 2.6.10). In Debian 13 both `slapd` and
`libldap2` **switched their TLS backend from GnuTLS to OpenSSL**
([release announcement](https://www.debian.org/News/2025/20250809)). Confirmed directly on
the image: `ldd /usr/sbin/slapd` links `libssl.so.3` and `libcrypto.so.3`, with no GnuTLS
present.

Two consequences for whoever does the VPS work:

1. **Most OpenLDAP TLS tutorials will not apply.** Available `olcTLS*` options and their
   behaviour changed with the backend. Anything written for Debian 12 or earlier is
   GnuTLS-era. Check against the OpenSSL-backed documentation, not the first search result.
2. **If no TLS CA certificates are configured, the system default trust store is now loaded
   automatically.** That is a behaviour change and it is quietly permissive — it means
   "no CA configured" no longer means "trust nothing". Configure trusted CAs **explicitly**
   rather than relying on the default.

TLS was out of scope locally on purpose (certificates for `ldap` on a compose network buy
nothing), but this note exists now so the VPS work does not start from a stale recipe.

---

## 3. Human login: `/debug/token` → Authorization Code + PKCE

| | |
|---|---|
| **PoC** | `POST /debug/token` — takes `username`, `password`, `client_id` over HTTP |
| **Production** | OAuth 2.0 Authorization Code + PKCE |
| **Extension point** | `idp/src/routes/debug.ts` (whole file) and `idp/src/app.ts:50` (the conditional mount) |

The route exists for exactly one reason: to validate the human token format end to end
without first building the OAuth flow, so `docs/CONTRACT.md` could be frozen and handed to
seven teams while the flow was still being built.

- It is mounted **only** when `DEBUG_TOKEN_ENABLED=true`. The default is off, so the path
  404s rather than 401s.
- The migration is **deletion**. Nothing depends on it: `docs/CONTRACT.md` §6 already tells
  consuming teams not to integrate against it, and the token it returns is identical to what
  the real flow will return.
- The audience-resolution logic in it (`client.audiences` lookup) is **not** throwaway — that
  same registry check belongs in the authorization-code flow.

---

## 4. Client secret hashing: scrypt → Argon2id

| | |
|---|---|
| **PoC** | `node:crypto` scrypt, N=16384, r=8, p=1, 32-byte key, 16-byte salt |
| **Production design** | Argon2id |
| **Extension point** | `idp/src/clients.ts` — stored hashes carry an algorithm prefix |

Format: `scrypt$<N>$<r>$<p>$<saltB64>$<hashB64>`.

scrypt was chosen over Argon2id/bcrypt for the PoC because it is in the Node standard
library: no `node-gyp`, no native build in the Docker image, no prebuilt-binary gamble on
ARM. It is in the same memory-hard class, so this is a packaging decision, not a security
downgrade.

Because the hash carries its own parameters, `verifySecret` derives with the **stored**
`N`/`r`/`p` rather than today's constants — so raising the cost factor does not invalidate
existing hashes. Adding Argon2id is a branch on the prefix plus a dependency; existing
`scrypt$...` hashes keep verifying, and clients migrate as secrets are rotated.

Secrets are **never** stored in LDAP. Services are not people.

---

## 5. Key rotation: single key → scheduled rotation

| | |
|---|---|
| **PoC** | one RSA-2048 key, generated on first boot into the `idp-keys` volume |
| **Production** | scheduled rotation with an overlap window |
| **Extension point** | `idp/src/keys.ts:63` (`loadKeystore` reads **every** `*.pem` in the directory) and the `ACTIVE_KID` env var |

The mechanism is already in place and is the part that is hard to retrofit:

- `kid` is the **RFC 7638 JWK thumbprint** of the public key, not a random string. The same
  key file always yields the same `kid`, so restart-stability is structural rather than
  something a test has to keep honest.
- The JWKS publishes **all** keys found, not just the active one. Dropping a second `.pem`
  into the volume immediately publishes both; flipping `ACTIVE_KID` moves signing to the new
  one; deleting the old file after the TTL window completes the rotation.

What is missing is only the *scheduling* — no automation, no key lifecycle metadata, no
"pending"/"retired" states.

---

## 6. Issuer and discovery

| | |
|---|---|
| **PoC** | `iss` is the fixed string `https://idp.citypass.local`; the JWKS URL is a separate config value |
| **Production** | real hostname, HTTPS, and (optionally) `/.well-known/openid-configuration` |
| **Extension point** | `ISSUER` and `JWKS_URL` env vars; `idp/src/verifier/index.ts` takes `jwksUrl` explicitly |

`iss` is an **identifier, not an address**. Nothing resolves it and the verifier must never
dereference it — that is why `jwksUrl` is a separate parameter rather than derived. Adding a
discovery document later does not change this: the verifier still takes an explicit URL.

---

## Not a divergence

`POST /oauth/token` with `grant_type=client_credentials` ships **as-is**. It is the real
contract agreed with Group 1, not a PoC stand-in.
