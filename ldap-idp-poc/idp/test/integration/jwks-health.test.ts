import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { debugToken, getJson, headerOf, startHarness, type Harness } from './helpers';

let h: Harness;
beforeAll(async () => { h = await startHarness(); });
afterAll(async () => { await h?.stop(); });

describe('acceptance 10: the token header kid matches a published JWKS key', () => {
  it('publishes a JWKS with kid and alg per key, and no private material', async () => {
    const res = await fetch(`${h.base}/.well-known/jwks.json`);
    expect(res.status).toBe(200);
    const jwks: any = await res.json();
    expect(Array.isArray(jwks.keys)).toBe(true);
    expect(jwks.keys.length).toBeGreaterThanOrEqual(1);
    for (const k of jwks.keys) {
      expect(k.kty).toBe('RSA');
      expect(typeof k.kid).toBe('string');
      expect(k.alg).toBe('RS256');
      expect(k.use).toBe('sig');
      expect(typeof k.n).toBe('string');
      expect(k.e).toBe('AQAB');
      // Private components must never be published.
      for (const priv of ['d', 'p', 'q', 'dp', 'dq', 'qi']) expect(k[priv]).toBeUndefined();
    }
  });

  it('signs with a key that is present in the JWKS', async () => {
    const { json } = await debugToken(h.base, {
      username: 'jperez', password: 'Password123!', client_id: 'citypass-reclamos-web',
    });
    const kid = headerOf(json.access_token).kid;
    const jwks = await getJson(`${h.base}/.well-known/jwks.json`);
    expect(jwks.keys.map((k: any) => k.kid)).toContain(kid);
  });

  it('requires no authentication', async () => {
    const res = await fetch(`${h.base}/.well-known/jwks.json`, { headers: {} });
    expect(res.status).toBe(200);
  });
});

describe('healthz', () => {
  it('reports ok and performs a real search against LDAP', async () => {
    const res = await fetch(`${h.base}/healthz`);
    expect(res.status).toBe(200);
    const body: any = await res.json();
    expect(body.status).toBe('ok');
    expect(body.ldap).toBe('ok');
    expect(typeof body.kid).toBe('string');
  });

  it('reports degraded when the directory is unreachable', async () => {
    // Port 1 is reserved and refuses instantly, so this exercises the failure
    // path without waiting on the connect timeout.
    const bad = await startHarness({ TEST_LDAP_URL: 'ldap://127.0.0.1:1', LDAP_URL: 'ldap://127.0.0.1:1' });
    try {
      const res = await fetch(`${bad.base}/healthz`);
      expect(res.status).toBe(503);
      expect(((await res.json()) as any).ldap).toBe('unreachable');
    } finally {
      await bad.stop();
    }
  });
});

describe('debug endpoint flag', () => {
  it('404s when DEBUG_TOKEN_ENABLED is not true', async () => {
    const off = await startHarness({ DEBUG_TOKEN_ENABLED: 'false' });
    try {
      const res = await fetch(`${off.base}/debug/token`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username: 'jperez', password: 'Password123!', client_id: 'citypass-reclamos-web' }),
      });
      expect(res.status).toBe(404);
    } finally {
      await off.stop();
    }
  });
});
