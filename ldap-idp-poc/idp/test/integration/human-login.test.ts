import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { debugToken, headerOf, payloadOf, startHarness, type Harness } from './helpers';

const RECLAMOS = 'citypass-reclamos-web';
const MOVILIDAD = 'citypass-movilidad-web';
const PORTAL = 'citypass-portal';
const PW = 'Password123!';

let h: Harness;
beforeAll(async () => { h = await startHarness(); });
afterAll(async () => { await h?.stop(); });

describe('acceptance 2: a valid login returns a human JWT in the frozen format', () => {
  it('issues a token with every contracted claim', async () => {
    const { status, json } = await debugToken(h.base, { username: 'jperez', password: PW, client_id: RECLAMOS });
    expect(status).toBe(200);
    expect(json.token_type).toBe('Bearer');
    expect(typeof json.expires_in).toBe('number');

    const p = payloadOf(json.access_token);
    expect(p.iss).toBe('https://idp.citypass.local');
    // sub is the employeeNumber from LDAP, never the username or the email.
    expect(p.sub).toBe('CP-8f7d2c10');
    expect(Array.isArray(p.aud)).toBe(true);
    expect(p.aud).toEqual(['citypass-reclamos-api']);
    expect(p.token_use).toBe('human');
    expect(p.ver).toBe(1);
    expect(p.preferred_username).toBe('jperez');
    expect(p.roles).toEqual(['reclamos:agente']);
    expect(typeof p.iat).toBe('number');
    expect(typeof p.exp).toBe('number');
    expect(p.exp - p.iat).toBe(900);
    expect(typeof p.jti).toBe('string');

    // The payload is signed but NOT encrypted: nothing sensitive may appear.
    expect(JSON.stringify(p)).not.toContain('Password');
    expect(p.userPassword).toBeUndefined();
    expect(p.mail).toBeUndefined();
  });

  it('puts the signing kid in the header', async () => {
    const { json } = await debugToken(h.base, { username: 'jperez', password: PW, client_id: RECLAMOS });
    const hdr = headerOf(json.access_token);
    expect(hdr.alg).toBe('RS256');
    expect(typeof hdr.kid).toBe('string');
  });
});

describe('acceptance 3 + 4: authentication failures are generic and indistinguishable', () => {
  it('rejects an empty password', async () => {
    const { status, json } = await debugToken(h.base, { username: 'jperez', password: '', client_id: RECLAMOS });
    expect(status).toBe(401);
    expect(json.access_token).toBeUndefined();
  });

  it('rejects a whitespace-only password', async () => {
    // Not an unauthenticated bind, but still worth pinning: it must fail.
    const { status } = await debugToken(h.base, { username: 'jperez', password: '   ', client_id: RECLAMOS });
    expect(status).toBe(401);
  });

  it('returns byte-identical responses for a nonexistent user and a wrong password', async () => {
    const missing = await debugToken(h.base, { username: 'nosuchuser', password: PW, client_id: RECLAMOS });
    const wrong = await debugToken(h.base, { username: 'jperez', password: 'wrong-password', client_id: RECLAMOS });
    const empty = await debugToken(h.base, { username: 'jperez', password: '', client_id: RECLAMOS });

    expect(missing.status).toBe(401);
    expect(wrong.status).toBe(missing.status);
    expect(empty.status).toBe(missing.status);
    expect(JSON.stringify(wrong.json)).toBe(JSON.stringify(missing.json));
    expect(JSON.stringify(empty.json)).toBe(JSON.stringify(missing.json));
  });

  it('rejects a username containing LDAP filter metacharacters without erroring', async () => {
    // If the filter were built by concatenation this would match everything.
    const inject = await debugToken(h.base, { username: '*', password: PW, client_id: RECLAMOS });
    const inject2 = await debugToken(h.base, { username: 'jperez)(uid=*', password: PW, client_id: RECLAMOS });
    expect(inject.status).toBe(401);
    expect(inject2.status).toBe(401);
  });
});

describe('acceptance 5: roles are filtered to the requested audience', () => {
  it('gives the dual-module user only Reclamos roles for the Reclamos audience', async () => {
    const { status, json } = await debugToken(h.base, { username: 'mgomez', password: PW, client_id: RECLAMOS });
    expect(status).toBe(200);
    const p = payloadOf(json.access_token);
    expect(p.sub).toBe('CP-4a1e9b73');
    expect(p.aud).toEqual(['citypass-reclamos-api']);
    expect(p.roles).toEqual(['reclamos:supervisor']);
    expect(p.roles).not.toContain('movilidad:consulta');
  });

  it('gives the same user only Movilidad roles for the Movilidad audience', async () => {
    const { json } = await debugToken(h.base, { username: 'mgomez', password: PW, client_id: MOVILIDAD });
    const p = payloadOf(json.access_token);
    expect(p.aud).toEqual(['citypass-movilidad-api']);
    expect(p.roles).toEqual(['movilidad:consulta']);
  });

  it('issues an empty roles array when the user holds nothing for that audience', async () => {
    // Authentication succeeded; authorization is the resource server's job.
    const { status, json } = await debugToken(h.base, { username: 'jperez', password: PW, client_id: MOVILIDAD });
    expect(status).toBe(200);
    expect(payloadOf(json.access_token).roles).toEqual([]);
  });
});

describe('acceptance 5b: the audience registry cannot be bypassed', () => {
  it('rejects an audience the client is not registered for', async () => {
    const { status, json } = await debugToken(h.base, {
      username: 'mgomez', password: PW, client_id: RECLAMOS, audience: 'citypass-movilidad-api',
    });
    expect(status).toBe(400);
    expect(json.error).toBe('invalid_request');
    expect(json.access_token).toBeUndefined();
  });

  it('accepts an audience the client IS registered for', async () => {
    const { status, json } = await debugToken(h.base, {
      username: 'mgomez', password: PW, client_id: PORTAL, audience: 'citypass-movilidad-api',
    });
    expect(status).toBe(200);
    expect(payloadOf(json.access_token).roles).toEqual(['movilidad:consulta']);
  });

  it('requires an explicit audience when the client may request several', async () => {
    const { status, json } = await debugToken(h.base, { username: 'mgomez', password: PW, client_id: PORTAL });
    expect(status).toBe(400);
    expect(json.error).toBe('invalid_request');
  });

  it('rejects an unknown client_id', async () => {
    const { status, json } = await debugToken(h.base, { username: 'mgomez', password: PW, client_id: 'nope' });
    expect(status).toBe(401);
    expect(json.error).toBe('invalid_client');
  });

  it('refuses to mint a human token for a service-only client', async () => {
    const { status, json } = await debugToken(h.base, { username: 'mgomez', password: PW, client_id: 'grupo5' });
    expect(status).toBe(400);
    expect(json.error).toBe('invalid_request');
  });
});

describe('acceptance 6: an unmapped LDAP group produces no roles', () => {
  it('ignores app-reclamos-auditor, which has no mapping entry', async () => {
    const { status, json } = await debugToken(h.base, { username: 'lrossi', password: PW, client_id: RECLAMOS });
    expect(status).toBe(200);
    const p = payloadOf(json.access_token);
    expect(p.sub).toBe('CP-2c6d0f45');
    // The group name follows the convention exactly. Roles are not derived
    // from the CN, so it grants nothing.
    expect(p.roles).toEqual([]);
  });
});

describe('acceptance 7: the placeholder is never a principal and never a role', () => {
  it('cannot authenticate', async () => {
    const { status } = await debugToken(h.base, {
      username: 'empty-group-placeholder', password: PW, client_id: MOVILIDAD,
    });
    expect(status).toBe(401);
  });

  it('never yields movilidad:supervisor to any human', async () => {
    for (const username of ['jperez', 'mgomez', 'lrossi']) {
      const { json } = await debugToken(h.base, { username, password: PW, client_id: MOVILIDAD });
      expect(payloadOf(json.access_token).roles).not.toContain('movilidad:supervisor');
    }
  });
});
