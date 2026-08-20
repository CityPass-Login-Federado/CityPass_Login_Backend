/**
 * Contract-verifier tests.
 *
 * Self-contained on purpose: no IdP process, no key files, no fixtures shared
 * with another track. The suite mints its own keys, serves its own JWKS over a
 * real loopback HTTP server (jose's `createRemoteJWKSet` does a real fetch), and
 * hand-rolls the hostile tokens. If this file passes, the verifier is correct
 * against the contract regardless of what the rest of the repo is doing.
 */

import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { SignJWT, calculateJwkThumbprint, exportJWK, generateKeyPair, type JWK } from 'jose';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  TokenVerificationError,
  verifyCityPassToken,
  type TokenVerificationCode,
  type VerifyOptions,
} from '../../src/verifier/index';

type KeyPair = Awaited<ReturnType<typeof generateKeyPair>>;

const ISSUER = 'https://idp.citypass.local';
const AUDIENCE = 'citypass-reclamos-api';

/** The key whose public half is published in the JWKS. */
let idpKeys: KeyPair;
/** A structurally identical key that is NOT in the JWKS. */
let attackerKeys: KeyPair;
let idpKid: string;
let idpPublicJwk: JWK;
let server: Server;
let jwksUrl: string;

function now(): number {
  return Math.floor(Date.now() / 1000);
}

function humanPayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const iat = now();
  return {
    iss: ISSUER,
    sub: 'CP-8f7d2c10',
    aud: [AUDIENCE],
    token_use: 'human',
    ver: 1,
    preferred_username: 'jperez',
    roles: ['reclamos:agente'],
    iat,
    exp: iat + 900,
    jti: '0f6b6a9e-1f4c-4a2e-9a1d-5f0a5f1c2d3e',
    ...overrides,
  };
}

function servicePayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const iat = now();
  return {
    iss: ISSUER,
    sub: 'grupo5',
    aud: ['citypass'],
    token_use: 'service',
    ver: 1,
    namespace: 'com.citypass.reclamos',
    iat,
    exp: iat + 900,
    jti: 'svc-0f6b6a9e-1f4c-4a2e-9a1d-5f0a5f1c2d3e',
    ...overrides,
  };
}

/** Signs with the real IdP key and the real kid, i.e. an honest token. */
function mint(payload: Record<string, unknown>): Promise<string> {
  return new SignJWT(payload)
    .setProtectedHeader({ alg: 'RS256', kid: idpKid, typ: 'JWT' })
    .sign(idpKeys.privateKey);
}

function options(overrides: Partial<VerifyOptions> = {}): VerifyOptions {
  return { jwksUrl, issuer: ISSUER, audience: AUDIENCE, tokenUse: 'human', ...overrides };
}

/** Asserts the call rejects with a TokenVerificationError carrying `code`. */
async function expectRejection(
  run: () => Promise<unknown>,
  code: TokenVerificationCode,
): Promise<TokenVerificationError> {
  try {
    await run();
  } catch (err) {
    expect(err).toBeInstanceOf(TokenVerificationError);
    expect((err as TokenVerificationError).code).toBe(code);
    return err as TokenVerificationError;
  }
  throw new Error(`expected verification to reject with code "${code}", it resolved instead`);
}

beforeAll(async () => {
  idpKeys = await generateKeyPair('RS256', { modulusLength: 2048, extractable: true });
  attackerKeys = await generateKeyPair('RS256', { modulusLength: 2048, extractable: true });

  const exported = await exportJWK(idpKeys.publicKey);
  // Same recipe as src/keys.ts: publish only n/e, and derive the kid as the
  // RFC 7638 thumbprint so the fixture matches production kid semantics.
  idpPublicJwk = { kty: exported.kty, n: exported.n, e: exported.e };
  idpKid = await calculateJwkThumbprint(idpPublicJwk, 'sha256');

  const body = JSON.stringify({
    keys: [{ ...idpPublicJwk, kid: idpKid, alg: 'RS256', use: 'sig' }],
  });
  server = createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(body);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  jwksUrl = `http://127.0.0.1:${port}/.well-known/jwks.json`;
});

afterAll(async () => {
  await new Promise<void>((resolve, reject) =>
    server.close((err) => (err ? reject(err) : resolve())),
  );
});

describe('verifyCityPassToken - happy paths', () => {
  it('accepts a valid human token and returns its claims', async () => {
    const claims = await verifyCityPassToken(await mint(humanPayload()), options());

    if (claims.token_use !== 'human') throw new Error('expected human claims');
    expect(claims.sub).toBe('CP-8f7d2c10');
    expect(claims.preferred_username).toBe('jperez');
    expect(claims.roles).toEqual(['reclamos:agente']);
    expect(claims.aud).toEqual([AUDIENCE]);
    expect(claims.iss).toBe(ISSUER);
    expect(claims.ver).toBe(1);
    expect(typeof claims.exp).toBe('number');
    expect(claims.jti).toBe('0f6b6a9e-1f4c-4a2e-9a1d-5f0a5f1c2d3e');
  });

  it('accepts a valid service token and returns its claims', async () => {
    const claims = await verifyCityPassToken(
      await mint(servicePayload()),
      options({ audience: 'citypass', tokenUse: 'service' }),
    );

    if (claims.token_use !== 'service') throw new Error('expected service claims');
    expect(claims.sub).toBe('grupo5');
    expect(claims.namespace).toBe('com.citypass.reclamos');
    expect(claims.aud).toEqual(['citypass']);
  });

  it('accepts a human token with no roles for this audience', async () => {
    const claims = await verifyCityPassToken(await mint(humanPayload({ roles: [] })), options());

    if (claims.token_use !== 'human') throw new Error('expected human claims');
    expect(claims.roles).toEqual([]);
  });

  it('accepts one audience out of several in the aud array', async () => {
    const claims = await verifyCityPassToken(
      await mint(humanPayload({ aud: ['citypass-otra-api', AUDIENCE] })),
      options(),
    );
    expect(claims.aud).toEqual(['citypass-otra-api', AUDIENCE]);
  });
});

describe('verifyCityPassToken - signature and algorithm', () => {
  it('rejects a token signed with a key that is not in the JWKS', async () => {
    // Correct kid in the header, so key resolution succeeds and the signature
    // check is what has to catch this.
    const forged = await new SignJWT(humanPayload())
      .setProtectedHeader({ alg: 'RS256', kid: idpKid, typ: 'JWT' })
      .sign(attackerKeys.privateKey);

    await expectRejection(() => verifyCityPassToken(forged, options()), 'signature');
  });

  it('rejects alg: none', async () => {
    const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
    const payload = Buffer.from(JSON.stringify(humanPayload())).toString('base64url');
    const unsecured = `${header}.${payload}.`;

    await expectRejection(() => verifyCityPassToken(unsecured, options()), 'algorithm');
  });

  it('rejects HS256 signed with the RSA public modulus as the HMAC secret', async () => {
    // The classic RS->HS confusion: the "secret" is public, so if the verifier
    // took `alg` from the header this token would validate for anyone.
    const secret = Buffer.from(idpPublicJwk.n as string, 'base64url');
    const confused = await new SignJWT(humanPayload())
      .setProtectedHeader({ alg: 'HS256', kid: idpKid, typ: 'JWT' })
      .sign(new Uint8Array(secret));

    await expectRejection(() => verifyCityPassToken(confused, options()), 'algorithm');
  });

  it('rejects a token whose kid is not in the JWKS', async () => {
    const unknownKid = await new SignJWT(humanPayload())
      .setProtectedHeader({ alg: 'RS256', kid: 'not-a-published-kid', typ: 'JWT' })
      .sign(idpKeys.privateKey);

    await expectRejection(() => verifyCityPassToken(unknownKid, options()), 'key');
  });

  it('rejects a string that is not a JWT at all', async () => {
    await expectRejection(() => verifyCityPassToken('definitely-not-a-jwt', options()), 'malformed');
  });

  it('never echoes the token in the error message', async () => {
    const forged = await new SignJWT(humanPayload())
      .setProtectedHeader({ alg: 'RS256', kid: idpKid, typ: 'JWT' })
      .sign(attackerKeys.privateKey);

    const err = await expectRejection(() => verifyCityPassToken(forged, options()), 'signature');
    expect(err.message).not.toContain(forged);
    expect(err.message).not.toContain('jperez');
  });
});

describe('verifyCityPassToken - issuer and audience', () => {
  it('rejects a foreign issuer', async () => {
    const token = await mint(humanPayload({ iss: 'https://evil.example.com' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'issuer');
  });

  it('rejects an issuer that merely shares a prefix', async () => {
    const token = await mint(humanPayload({ iss: `${ISSUER}.evil.com` }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'issuer');
  });

  it('rejects a token minted for a different audience', async () => {
    const token = await mint(humanPayload({ aud: ['citypass-turnos-api'] }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'audience');
  });
});

describe('verifyCityPassToken - expiry and clock skew', () => {
  it('rejects a token that expired long ago', async () => {
    const iat = now() - 7200;
    const token = await mint(humanPayload({ iat, exp: iat + 900 }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'expired');
  });

  it('accepts a token that expired 20s ago with the default 45s tolerance', async () => {
    const token = await mint(humanPayload({ iat: now() - 920, exp: now() - 20 }));
    const claims = await verifyCityPassToken(token, options());
    expect(claims.sub).toBe('CP-8f7d2c10');
  });

  it('rejects that same token when the tolerance is 0', async () => {
    const token = await mint(humanPayload({ iat: now() - 920, exp: now() - 20 }));
    await expectRejection(
      () => verifyCityPassToken(token, options({ clockToleranceSeconds: 0 })),
      'expired',
    );
  });

  it('rejects a token with no exp at all', async () => {
    const payload = humanPayload();
    delete payload['exp'];
    const token = await mint(payload);
    await expectRejection(() => verifyCityPassToken(token, options()), 'expired');
  });
});

describe('verifyCityPassToken - token_use', () => {
  it('rejects a human token where a service token is expected', async () => {
    const token = await mint(humanPayload());
    await expectRejection(
      () => verifyCityPassToken(token, options({ tokenUse: 'service' })),
      'token_use',
    );
  });

  it('rejects a service token where a human token is expected', async () => {
    const token = await mint(servicePayload({ aud: [AUDIENCE] }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'token_use');
  });

  it('rejects an unknown token_use value', async () => {
    const token = await mint(humanPayload({ token_use: 'robot' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'token_use');
  });

  it('rejects a missing token_use', async () => {
    const payload = humanPayload();
    delete payload['token_use'];
    const token = await mint(payload);
    await expectRejection(() => verifyCityPassToken(token, options()), 'token_use');
  });
});

describe('verifyCityPassToken - claim shape and types', () => {
  it('rejects ver as the string "1"', async () => {
    const token = await mint(humanPayload({ ver: '1' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects an unsupported ver', async () => {
    const token = await mint(humanPayload({ ver: 2 }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects aud as a bare string instead of an array', async () => {
    // jose is happy with a string aud and the audience check passes; the array
    // requirement is ours, and it has to be enforced on the raw payload.
    const token = await mint(humanPayload({ aud: AUDIENCE }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects roles as a bare string instead of an array', async () => {
    const token = await mint(humanPayload({ roles: 'reclamos:agente' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects roles containing a non-string', async () => {
    const token = await mint(humanPayload({ roles: ['reclamos:agente', 7] }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects an empty preferred_username', async () => {
    const token = await mint(humanPayload({ preferred_username: '' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects a human token with no preferred_username', async () => {
    const payload = humanPayload();
    delete payload['preferred_username'];
    const token = await mint(payload);
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects an empty sub', async () => {
    const token = await mint(humanPayload({ sub: '' }));
    await expectRejection(() => verifyCityPassToken(token, options()), 'claims');
  });

  it('rejects a service token with no namespace', async () => {
    const payload = servicePayload();
    delete payload['namespace'];
    const token = await mint(payload);
    await expectRejection(
      () => verifyCityPassToken(token, options({ audience: 'citypass', tokenUse: 'service' })),
      'claims',
    );
  });
});
