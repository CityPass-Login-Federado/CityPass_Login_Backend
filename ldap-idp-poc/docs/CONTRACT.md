# CityPass+ token contract

**Audience:** the seven teams building business modules. You do not need to know anything
about how the IdP works internally to use this document — you need to validate tokens it
issues, and this tells you exactly how.

**Status:** frozen for the PoC. The token *shapes* below will not change without a `ver`
bump and an announcement. The *endpoints* are partly provisional — see
[What is not frozen](#what-is-not-frozen).

**This document is the contract.** `idp/src/verifier/index.ts` is a reference
implementation of it in TypeScript. If you are on Java, Python, .NET or anything else,
implement the seven rules below against your own JWT library — do not try to consume the
TypeScript.

---

## 1. What you are receiving

A signed JWT (JWS, compact serialization). It is **signed but not encrypted**: anyone
holding the token can read the payload. Do not treat it as a secret store, and do not put
anything in it yourself.

Two kinds of token exist and they are **not interchangeable**:

| | Human token | Service token |
|---|---|---|
| Represents | a logged-in person | a backend service calling another backend |
| `token_use` | `"human"` | `"service"` |
| `sub` | the person's `employeeNumber` | the calling service's `client_id` |
| Carries roles? | yes, `roles` | no — it has `namespace` instead |
| Issued by | the login flow | `POST /oauth/token`, `grant_type=client_credentials` |

**You must require the one you expect.** Accepting either is a privilege-escalation bug:
a service token has no `roles` claim, so role checks silently pass or silently fail
depending on how you wrote them.

---

## 2. Token shapes

### Human token

```json
{
  "iss": "https://idp.citypass.local",
  "sub": "CP-8f7d2c10",
  "aud": ["citypass-reclamos-api"],
  "token_use": "human",
  "ver": 1,
  "preferred_username": "jperez",
  "roles": ["reclamos:agente"],
  "iat": 1754600000,
  "exp": 1754600900,
  "jti": "a1b2c3d4-..."
}
```

| Claim | Type | Meaning |
|---|---|---|
| `iss` | string | Always exactly `https://idp.citypass.local`. It is an **identifier, not a URL you fetch** — nothing resolves it. |
| `sub` | string | The person's `employeeNumber` from LDAP, format `CP-xxxxxxxx`. **This is the only stable user identifier.** It never changes, even if the person's username, name or email does. Use it as your foreign key. |
| `aud` | **array** of strings | Which API the token is for. Always an array, even with one element. |
| `token_use` | string | `"human"`. |
| `ver` | **number** | Payload version. Currently `1`. A different value means the shape changed — reject it. |
| `preferred_username` | string | The LDAP `uid`. **For display only.** It is not guaranteed stable; never key data on it. |
| `roles` | array of strings | Roles for *your* audience only, format `<module>:<role>`. **An empty array is valid and normal** — it means the person authenticated but holds no roles in your module. Handle it; do not treat it as an error. |
| `iat` / `exp` | number | Unix seconds. Lifetime is 10–15 minutes. |
| `jti` | string | Opaque unique id. Do not parse it. |

### Service token

```json
{
  "iss": "https://idp.citypass.local",
  "sub": "grupo5",
  "aud": ["citypass"],
  "token_use": "service",
  "ver": 1,
  "namespace": "com.citypass.reclamos",
  "iat": 1754600000,
  "exp": 1754600900,
  "jti": "svc-91ab..."
}
```

| Claim | Type | Meaning |
|---|---|---|
| `sub` | string | The calling service's `client_id` (e.g. `grupo5`). |
| `aud` | array | `["citypass"]` — the platform-internal audience. |
| `namespace` | string | The event-bus namespace the service owns, e.g. `com.citypass.reclamos`. Use it to decide what the service may publish or consume. |
| `jti` | string | Opaque. The `svc-` prefix is guaranteed; the rest is not parseable. |

### Header

```json
{ "alg": "RS256", "kid": "2MO39620Dn9sfYF9I3lZ9MS82hSk_Bhw5uGYnvdXliA", "typ": "JWT" }
```

`kid` identifies which published key signed the token. Your library uses it to pick the
right key from the JWKS. **Do not hardcode a key** — `kid` exists so keys can be rotated
without breaking you.

---

## 3. Audiences

| Audience | Who it is for |
|---|---|
| `citypass-reclamos-api` | the Reclamos module's API |
| `citypass-movilidad-api` | the Movilidad module's API |
| `citypass` | platform-internal, service tokens |

Your service has exactly one audience. **Validate that yours is present in `aud`.** A token
minted for another module must not be accepted by you, even though it is perfectly valid and
correctly signed. This is what stops a token from one module being replayed against another.

New modules get a new audience string; ask the identity team (Group 2) rather than inventing
one.

---

## 4. Where the keys come from

```
GET /.well-known/jwks.json      (public, no authentication)
```

Response:

```json
{ "keys": [ { "kty": "RSA", "kid": "...", "alg": "RS256", "use": "sig", "n": "...", "e": "AQAB" } ] }
```

- **The array may contain more than one key.** That is deliberate — it is how rotation
  works. Match on `kid`; never assume `keys[0]`.
- Cache it, but re-fetch when you see an unknown `kid`. Most JWT libraries do this for you
  (`createRemoteJWKSet` in jose, `JwkProvider` in java-jwt, `PyJWKClient` in PyJWT).
  Do not fetch it on every request.
- Only public components (`n`, `e`) are ever published.

---

## 5. The seven validation rules

Do these, in this order, **on every request**. Skipping any one of them is a security bug,
not a performance optimisation.

1. **Verify the signature** using the JWKS key whose `kid` matches the token header.
2. **Restrict the algorithm to an explicit allowlist: `["RS256"]`.** This single rule is
   what blocks two whole classes of attack: `alg: none` (a token with no signature at all)
   and algorithm-family confusion (an attacker signs with HS256 using the *public* key as
   the HMAC secret, and a naive verifier accepts it). It must be an **allowlist**. Never a
   denylist, never "whatever the header says".
3. **Validate `iss` matches exactly** `https://idp.citypass.local`. String equality.
4. **Validate your own audience is present in `aud`.**
5. **Validate `exp`**, allowing 30–60 seconds of clock skew (we use 45). Also require `exp`
   to be *present* — some libraries silently skip the expiry check when the claim is absent.
6. **Require the expected `token_use`** (`"human"` or `"service"`). Reject the other kind.
7. **Validate the shape and types of the claims you use for authorization**:
   `ver === 1` (the number, not the string), `sub` a non-empty string, `aud` an **array**,
   and for human tokens `roles` an **array of strings** (empty is fine),
   `preferred_username` a non-empty string; for service tokens `namespace` a non-empty
   string.

**Always `verify`, never `decode`.** Every mainstream JWT library ships a `decode()` that
parses the payload without checking the signature. It exists for debugging. If it appears
anywhere in your request path, an attacker can hand you any claims they like.

### Language notes

- **Node / TypeScript** — `jose`. `jwtVerify(token, createRemoteJWKSet(url), { algorithms: ['RS256'], issuer, audience, clockTolerance: 45, requiredClaims: ['exp','iat','sub','jti'] })`, then check `token_use`, `ver`, and `Array.isArray(payload.aud)` yourself. Or import our reference implementation (§7).
- **Java** — `com.auth0:java-jwt` + `jwks-rsa`. Build the verifier with `Algorithm.RSA256(provider)` and `.withIssuer(...).withAudience(...).acceptLeeway(45)`, then assert the custom claims.
- **Python** — `PyJWT` + `PyJWKClient`. `jwt.decode(token, key, algorithms=["RS256"], issuer=..., audience=..., leeway=45, options={"require": ["exp","iat","sub"]})`. Note PyJWT's function is *named* `decode` but does verify — the dangerous one is `options={"verify_signature": False}`.
- **.NET** — `Microsoft.IdentityModel.Tokens`. Set `ValidAlgorithms = ["RS256"]` explicitly; the defaults are broader than you want.

---

## 6. Getting a token

### Service-to-service — this endpoint is final

```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded
```

Credentials go **either** in an `Authorization: Basic base64(client_id:client_secret)`
header **or** in the body as `client_id` / `client_secret`. Both are supported. Sending both
at once is an error (400 `invalid_request`).

```
grant_type=client_credentials
```

Success (200):

```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 900 }
```

`expires_in` is a **JSON number** of seconds, never a string.

Errors follow RFC 6749 §5.2:

| Condition | Status | Body |
|---|---|---|
| bad/unknown client, wrong secret | 401 | `{"error":"invalid_client"}` + `WWW-Authenticate: Basic` |
| `grant_type` present but not `client_credentials` | 400 | `{"error":"unsupported_grant_type"}` |
| `grant_type` missing, or both auth methods used | 400 | `{"error":"invalid_request"}` |

All 401s are deliberately identical regardless of *why* they failed — you cannot use them to
discover which `client_id`s exist.

Ask Group 2 for your `client_id` and secret. Secrets are stored hashed; if you lose yours it
is reissued, not recovered.

### Human login — provisional

In the PoC only, `POST /debug/token` takes `username`, `password`, `client_id` and returns a
human token. **Do not integrate against it.** It exists so this document could be written
and frozen before the real flow was built. It is disabled by default and will be deleted.

The real flow will be **OAuth 2.0 Authorization Code + PKCE**. Your side of that is a
redirect and a code exchange; the token you end up holding is the one specified above, which
is the part you can start building against today.

---

## 7. Reference implementation

```ts
import { verifyCityPassToken, TokenVerificationError } from 'citypass-idp-poc/verifier';

const claims = await verifyCityPassToken(token, {
  jwksUrl: 'http://idp:8080/.well-known/jwks.json',
  issuer: 'https://idp.citypass.local',
  audience: 'citypass-reclamos-api',
  tokenUse: 'human',
  clockToleranceSeconds: 45,   // optional, this is the default
});
// claims is narrowed by token_use:
if (claims.token_use === 'human') claims.roles.includes('reclamos:agente');
```

Throws `TokenVerificationError` with a `code` field for logging:
`malformed | algorithm | key | signature | issuer | audience | expired | token_use | claims`.

Caveat: the package entry point resolves to raw TypeScript, so consuming it directly needs
`tsx` or a build step. Copying the file into your project is a legitimate option.

---

## What is not frozen

- `POST /debug/token` — provisional, will be deleted (see §6).
- The `iss` value and the JWKS URL will change when this is deployed to the VPS
  (`https://` and a real hostname). The *shapes* will not.
- New roles and new audiences will be added. Existing ones will not be renamed without a
  `ver` bump.
- Refresh tokens, SSO session and logout are not in the PoC at all.
