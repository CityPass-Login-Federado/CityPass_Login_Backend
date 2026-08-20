/**
 * CityPass+ token contract verifier -- REFERENCE IMPLEMENTATION.
 *
 * This module is what a Node/TypeScript consumer imports to validate a token
 * minted by the CityPass+ IdP. It talks only to the JWKS endpoint, so it can be
 * used with no IdP process reachable beyond that URL, and it never trusts an
 * unverified byte of the token.
 *
 * THIS FILE IS NOT THE CONTRACT. `docs/CONTRACT.md` is. This is one
 * implementation of it; teams on other stacks (Java, .NET, Python, Go) write
 * their own and must enforce the same seven rules, in this order:
 *
 *   1. resolve the signing key by the header `kid` against the published JWKS;
 *   2. accept ONLY algorithms on an explicit allowlist (`RS256`) -- an
 *      allowlist, never a denylist, is what defeats `alg: none` and RS/HS
 *      family confusion;
 *   3. `iss` must equal the expected issuer exactly;
 *   4. the consumer's own audience must appear in `aud`;
 *   5. `exp` must be in the future, within a small clock-skew tolerance;
 *   6. `token_use` must be the expected one -- a human token is not a service
 *      token and vice versa;
 *   7. every claim used for an authorization decision must be present and of
 *      the right JSON type.
 *
 * A note on rule 2 and on `decodeJwt`/`decodeProtectedHeader`: this module
 * calls `jwtVerify` and nothing else. The header `kid` and `alg` reach a
 * decision only through jose's own verification path, which checks the
 * allowlist before it resolves a key and checks the signature before it looks
 * at a single claim. There is no legitimate use of unverified token data here.
 */

import { createRemoteJWKSet, jwtVerify, type JWTPayload, type RemoteJWKSet } from 'jose';

/** The only signature algorithm the CityPass+ contract allows. */
const ALLOWED_ALGORITHMS = ['RS256'] as const;

/** The only payload version this verifier understands. Must match `TOKEN_VERSION`. */
const SUPPORTED_VERSION = 1;

/** Seconds of clock skew tolerated on `exp` when the caller does not say. */
const DEFAULT_CLOCK_TOLERANCE_SECONDS = 45;

export type TokenUse = 'human' | 'service';

/**
 * Why a token was rejected. Meant for logs and metrics, not for branching on
 * security decisions -- every one of these means "do not honour this token".
 */
export type TokenVerificationCode =
  /** The token is not a well-formed compact JWS at all. */
  | 'malformed'
  /** Header `alg` is not on the allowlist (`alg: none`, HS256 confusion, ...). */
  | 'algorithm'
  /** No JWKS key matched the header `kid`, or the JWKS could not be fetched. */
  | 'key'
  /** The signature did not verify against the resolved key. */
  | 'signature'
  /** `iss` is not the expected issuer. */
  | 'issuer'
  /** The caller's audience is not in `aud`. */
  | 'audience'
  /** `exp` is in the past (beyond tolerance), or absent, or not a number. */
  | 'expired'
  /** `token_use` is missing, unknown, or not the one the caller expected. */
  | 'token_use'
  /** A claim used for authorization is missing or has the wrong JSON type. */
  | 'claims';

/**
 * The single error type this module throws. The message is deliberately
 * generic: it never contains the token, a claim value, or any part of either,
 * because rejected tokens end up in log aggregators.
 */
export class TokenVerificationError extends Error {
  readonly code: TokenVerificationCode;

  constructor(code: TokenVerificationCode, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'TokenVerificationError';
    this.code = code;
  }
}

export interface VerifyOptions {
  /**
   * Where to fetch the signing keys. Deliberately NOT derived from the token's
   * `iss`: a token that could name its own key source would only be proving it
   * agrees with itself.
   */
  jwksUrl: string;
  /** The exact expected `iss`. Compared with `===`, no prefix matching. */
  issuer: string;
  /** This service's own audience. Must appear in the token's `aud` array. */
  audience: string;
  /** Which kind of token the caller is willing to accept here. */
  tokenUse: TokenUse;
  /** Clock skew allowance on `exp`, in seconds. Defaults to 45. */
  clockToleranceSeconds?: number;
}

/** Claims present on both token kinds. */
export interface BaseClaims {
  iss: string;
  /** `employeeNumber` for humans, `client_id` for services. Never a username. */
  sub: string;
  /** Always an array on the wire, even with a single element. */
  aud: string[];
  ver: number;
  iat: number;
  exp: number;
  jti: string;
}

export interface HumanClaims extends BaseClaims {
  token_use: 'human';
  preferred_username: string;
  /** Roles for THIS audience only. An empty array is legitimate. */
  roles: string[];
}

export interface ServiceClaims extends BaseClaims {
  token_use: 'service';
  namespace: string;
}

/**
 * Discriminated on `token_use`, so `if (claims.token_use === 'human')` narrows
 * to {@link HumanClaims} without a cast.
 */
export type CityPassClaims = HumanClaims | ServiceClaims;

/**
 * One `RemoteJWKSet` per JWKS URL, for the lifetime of the process.
 *
 * `createRemoteJWKSet` keeps its own cache and rate-limits refetches, but that
 * state lives on the instance: building a fresh one per verification would
 * fetch the JWKS on every request and hand an attacker a trivial amplifier
 * against the IdP.
 */
const jwksByUrl = new Map<string, RemoteJWKSet>();

function jwksFor(url: string): RemoteJWKSet {
  let jwks = jwksByUrl.get(url);
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(url));
    jwksByUrl.set(url, jwks);
  }
  return jwks;
}

/** Test/ops escape hatch: drops the cached JWKS resolvers. */
export function resetJwksCache(): void {
  jwksByUrl.clear();
}

function errorCode(err: unknown): string | undefined {
  const code = (err as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : undefined;
}

/** Maps a jose failure onto a contract error, without echoing the token. */
function asVerificationError(err: unknown): TokenVerificationError {
  const cause = { cause: err };
  switch (errorCode(err)) {
    case 'ERR_JOSE_ALG_NOT_ALLOWED':
      return new TokenVerificationError(
        'algorithm',
        'Token algorithm is not on the allowlist',
        cause,
      );
    case 'ERR_JWKS_NO_MATCHING_KEY':
      return new TokenVerificationError('key', 'No JWKS key matches the token kid', cause);
    case 'ERR_JWKS_MULTIPLE_MATCHING_KEYS':
      return new TokenVerificationError('key', 'Token kid is ambiguous in the JWKS', cause);
    case 'ERR_JWKS_TIMEOUT':
    case 'ERR_JWKS_INVALID':
    case 'ERR_JWK_INVALID':
      return new TokenVerificationError('key', 'Signing keys could not be resolved', cause);
    case 'ERR_JWS_SIGNATURE_VERIFICATION_FAILED':
      return new TokenVerificationError('signature', 'Token signature is invalid', cause);
    case 'ERR_JWS_INVALID':
    case 'ERR_JWT_INVALID':
      return new TokenVerificationError('malformed', 'Token is not a well-formed JWT', cause);
    case 'ERR_JWT_EXPIRED':
      return new TokenVerificationError('expired', 'Token is expired', cause);
    case 'ERR_JWT_CLAIM_VALIDATION_FAILED': {
      const claim = (err as { claim?: unknown }).claim;
      if (claim === 'iss') {
        return new TokenVerificationError('issuer', 'Token issuer is not the expected one', cause);
      }
      if (claim === 'aud') {
        return new TokenVerificationError(
          'audience',
          'Token audience does not include this service',
          cause,
        );
      }
      if (claim === 'exp' || claim === 'nbf' || claim === 'iat') {
        return new TokenVerificationError('expired', 'Token validity window is invalid', cause);
      }
      return new TokenVerificationError('claims', 'Token claim validation failed', cause);
    }
    default:
      // Unknown failures are treated as unverifiable signatures: the token has
      // not earned trust, whatever went wrong.
      return new TokenVerificationError('signature', 'Token could not be verified', cause);
  }
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function claimsFailure(detail: string): TokenVerificationError {
  return new TokenVerificationError('claims', `Token claim validation failed: ${detail}`);
}

/** Rules 6 and 7, on a payload whose signature is already verified. */
function validateClaims(payload: JWTPayload, expected: TokenUse): CityPassClaims {
  const tokenUse = payload['token_use'];
  if (tokenUse !== 'human' && tokenUse !== 'service') {
    throw new TokenVerificationError('token_use', 'Token has an unknown token_use');
  }
  if (tokenUse !== expected) {
    throw new TokenVerificationError(
      'token_use',
      `Expected a ${expected} token, got a ${tokenUse} token`,
    );
  }

  // Strict identity: `1` is the contract, `"1"` is a different type and a
  // strong hint that something upstream is stringifying the payload.
  if (payload['ver'] !== SUPPORTED_VERSION) {
    throw claimsFailure('ver must be the number 1');
  }

  if (!isNonEmptyString(payload.iss)) throw claimsFailure('iss must be a non-empty string');
  if (!isNonEmptyString(payload.sub)) throw claimsFailure('sub must be a non-empty string');
  if (!isNonEmptyString(payload.jti)) throw claimsFailure('jti must be a non-empty string');

  // jose accepts a bare-string `aud` and leaves it as it was on the wire. The
  // contract says array, always, so a string here is an off-contract token even
  // though its single value may well have matched.
  if (!isStringArray(payload.aud) || payload.aud.length === 0) {
    throw claimsFailure('aud must be a non-empty array of strings');
  }
  if (typeof payload.iat !== 'number') throw claimsFailure('iat must be a number');
  if (typeof payload.exp !== 'number') throw claimsFailure('exp must be a number');

  const base: BaseClaims = {
    iss: payload.iss,
    sub: payload.sub,
    aud: payload.aud,
    ver: SUPPORTED_VERSION,
    iat: payload.iat,
    exp: payload.exp,
    jti: payload.jti,
  };

  if (tokenUse === 'human') {
    const preferredUsername = payload['preferred_username'];
    if (!isNonEmptyString(preferredUsername)) {
      throw claimsFailure('preferred_username must be a non-empty string');
    }
    const roles = payload['roles'];
    // An empty array is VALID: a user who holds no role for this audience is
    // still authenticated, and the API decides what that means.
    if (!isStringArray(roles)) {
      throw claimsFailure('roles must be an array of strings');
    }
    return { ...base, token_use: 'human', preferred_username: preferredUsername, roles };
  }

  const namespace = payload['namespace'];
  if (!isNonEmptyString(namespace)) {
    throw claimsFailure('namespace must be a non-empty string');
  }
  return { ...base, token_use: 'service', namespace };
}

/**
 * Verifies a CityPass+ token and returns its claims.
 *
 * Throws {@link TokenVerificationError} -- and only that -- for every reason a
 * token can be rejected. A returned value means all seven contract rules held;
 * there is no partial success and no `valid: false` result to forget to check.
 *
 * @param token the raw compact JWS, with the `Bearer ` prefix already stripped
 * @param opts what this particular service is willing to accept
 */
export async function verifyCityPassToken(
  token: string,
  opts: VerifyOptions,
): Promise<CityPassClaims> {
  if (opts.tokenUse !== 'human' && opts.tokenUse !== 'service') {
    throw new TypeError(`VerifyOptions.tokenUse must be 'human' or 'service'`);
  }
  if (typeof token !== 'string' || token.length === 0) {
    throw new TokenVerificationError('malformed', 'Token is missing');
  }

  const jwks = jwksFor(opts.jwksUrl);

  let payload: JWTPayload;
  try {
    // Rules 1-5 in one call: kid resolution against the JWKS, the algorithm
    // allowlist, exact issuer, audience membership, and exp with tolerance.
    ({ payload } = await jwtVerify(token, jwks, {
      algorithms: [...ALLOWED_ALGORITHMS],
      issuer: opts.issuer,
      audience: opts.audience,
      clockTolerance: opts.clockToleranceSeconds ?? DEFAULT_CLOCK_TOLERANCE_SECONDS,
      // `exp` is optional in the JWT spec, and jose skips the check when it is
      // absent. Requiring it here is what stops a never-expiring token.
      requiredClaims: ['exp', 'iat', 'sub', 'jti'],
    }));
  } catch (err) {
    throw asVerificationError(err);
  }

  return validateClaims(payload, opts.tokenUse);
}
