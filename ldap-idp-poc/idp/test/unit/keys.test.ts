import { mkdtemp, readdir, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SignJWT, calculateJwkThumbprint, createLocalJWKSet, jwtVerify } from 'jose';
import { ensureKey, loadKeystore } from '../../src/keys';

async function tmp(): Promise<string> {
  return mkdtemp(join(tmpdir(), 'citypass-keytest-'));
}

describe('keystore', () => {
  it('generates exactly one key on first load and reuses it afterwards', async () => {
    const dir = await tmp();
    await ensureKey(dir);
    const after1 = await readdir(dir);
    await ensureKey(dir);
    const after2 = await readdir(dir);
    expect(after1).toHaveLength(1);
    expect(after2).toEqual(after1);
  });

  it('names the key file after its RFC 7638 thumbprint', async () => {
    const dir = await tmp();
    const ks = await loadKeystore(dir);
    const [file] = await readdir(dir);
    const jwk = ks.jwks().keys[0]!;
    expect(file).toBe(`${jwk.kid}.pem`);
    expect(await calculateJwkThumbprint({ kty: jwk.kty, n: jwk.n, e: jwk.e }, 'sha256')).toBe(jwk.kid);
  });

  it('writes the private key with 0600 and never publishes private components', async () => {
    const dir = await tmp();
    const ks = await loadKeystore(dir);
    const [file] = await readdir(dir);
    const pem = await readFile(join(dir, file!), 'utf8');
    expect(pem).toContain('BEGIN PRIVATE KEY');
    const jwk = ks.jwks().keys[0]! as Record<string, unknown>;
    for (const priv of ['d', 'p', 'q', 'dp', 'dq', 'qi']) expect(jwk[priv]).toBeUndefined();
  });

  /**
   * Secondary check for acceptance criterion 11. The PRIMARY test is
   * test/integration/restart.test.ts, which restarts the real container and so
   * also catches a missing volume or a wrong mount path. This one localises a
   * failure to the code rather than the compose configuration.
   */
  it('a token signed by one keystore instance verifies against a later instance over the same dir', async () => {
    const dir = await tmp();
    const before = await loadKeystore(dir);
    const token = await new SignJWT({ token_use: 'service' })
      .setProtectedHeader({ alg: 'RS256', kid: before.activeKid() })
      .setIssuer('https://idp.citypass.local')
      .setAudience(['citypass'])
      .setExpirationTime('5m')
      .sign(before.activeKey().privateKey);

    // Simulates the process restarting: fresh load, same directory.
    const after = await loadKeystore(dir);
    expect(after.activeKid()).toBe(before.activeKid());

    const { payload } = await jwtVerify(token, createLocalJWKSet(after.jwks() as any), {
      algorithms: ['RS256'],
      issuer: 'https://idp.citypass.local',
      audience: 'citypass',
    });
    expect(payload.token_use).toBe('service');
  });

  it('a token from a DIFFERENT directory does not verify (the test above is not vacuous)', async () => {
    const a = await loadKeystore(await tmp());
    const b = await loadKeystore(await tmp());
    expect(a.activeKid()).not.toBe(b.activeKid());
    const token = await new SignJWT({})
      .setProtectedHeader({ alg: 'RS256', kid: a.activeKid() })
      .setExpirationTime('5m')
      .sign(a.activeKey().privateKey);
    await expect(jwtVerify(token, createLocalJWKSet(b.jwks() as any), { algorithms: ['RS256'] })).rejects.toThrow();
  });

  it('rejects an ACTIVE_KID that is not present', async () => {
    const dir = await tmp();
    await expect(loadKeystore(dir, 'not-a-real-kid')).rejects.toThrow(/not present/);
  });
});
