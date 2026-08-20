import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import express from 'express';
import { decodeJwt } from 'jose';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { AppConfig } from '../../src/config';
import type { AppContext } from '../../src/context';
import { FileConfigStore } from '../../src/store';
import { loadKeystore } from '../../src/keys';
import { oauthRouter } from '../../src/routes/oauth';

/** Registered in config/idp.json as a service client. */
const SERVICE_CLIENT = 'grupo5';
const SERVICE_SECRET = 'grupo5-secret';
const SERVICE_NAMESPACE = 'com.citypass.reclamos';
/** Registered, but `secretHash: null` -- humans log in, this client never does. */
const HUMAN_CLIENT = 'citypass-reclamos-web';

const CONFIG_FILE = new URL('../../config/idp.json', import.meta.url).pathname;

let server: Server;
let baseUrl: string;
let keysDir: string;

function basic(clientId: string, clientSecret: string): string {
  return `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}`;
}

/** `Response.json()` is typed `unknown`; every body here is a JSON object. */
async function jsonBody(res: Response): Promise<Record<string, unknown>> {
  return (await res.json()) as Record<string, unknown>;
}

function tokenRequest(init: { headers?: Record<string, string>; form: Record<string, string> }) {
  return fetch(`${baseUrl}/oauth/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', ...init.headers },
    body: new URLSearchParams(init.form).toString(),
  });
}

beforeAll(async () => {
  keysDir = await mkdtemp(join(tmpdir(), 'idp-oauth-keys-'));

  const config: AppConfig = {
    port: 0,
    issuer: 'https://idp.citypass.local',
    jwksUrl: 'http://localhost:8080/.well-known/jwks.json',
    keysDir,
    tokenTtlSeconds: 900,
    ldapUrl: 'ldap://unused:389',
    ldapBindDn: 'cn=unused',
    ldapBindPw: 'unused',
    ldapPeopleBase: 'ou=People,dc=citypass,dc=local',
    ldapConnectTimeoutMs: 3000,
    ldapTimeoutMs: 5000,
    debugTokenEnabled: false,
    configFile: CONFIG_FILE,
  };

  const ctx: AppContext = {
    config,
    store: FileConfigStore.fromFile(CONFIG_FILE),
    keystore: await loadKeystore(keysDir),
    // The token endpoint must never reach for LDAP: services are not people.
    // A null here turns any such call into a loud TypeError.
    ldap: null as never,
  };

  const app = express();
  app.use(express.urlencoded({ extended: false }));
  app.use('/oauth', oauthRouter(ctx));

  server = await new Promise<Server>((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  await new Promise<void>((resolve, reject) => {
    server.close((err) => (err ? reject(err) : resolve()));
  });
  await rm(keysDir, { recursive: true, force: true });
});

describe('POST /oauth/token -- success', () => {
  it('issues a service token for Basic credentials', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: { grant_type: 'client_credentials' },
    });

    expect(res.status).toBe(200);
    const body = await jsonBody(res);
    // EXACTLY three fields: the contract is frozen, extras are a breaking change.
    expect(Object.keys(body).sort()).toEqual(['access_token', 'expires_in', 'token_type']);
    expect(body.token_type).toBe('Bearer');
    expect(typeof body.access_token).toBe('string');
    expect(typeof body.expires_in).toBe('number');
    expect(body.expires_in).toBe(900);
  });

  it('issues an identical-shaped token for body credentials', async () => {
    const res = await tokenRequest({
      form: {
        grant_type: 'client_credentials',
        client_id: SERVICE_CLIENT,
        client_secret: SERVICE_SECRET,
      },
    });

    expect(res.status).toBe(200);
    const body = await jsonBody(res);
    expect(body.token_type).toBe('Bearer');
    expect(typeof body.expires_in).toBe('number');
    expect(typeof body.access_token).toBe('string');
  });

  it('signs a service-shaped payload', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: { grant_type: 'client_credentials' },
    });
    const token = (await jsonBody(res)).access_token as string;

    const payload = decodeJwt(token);
    expect(payload.token_use).toBe('service');
    expect(payload.ver).toBe(1);
    expect(payload.namespace).toBe(SERVICE_NAMESPACE);
    expect(payload.sub).toBe(SERVICE_CLIENT);
    expect(payload.iss).toBe('https://idp.citypass.local');
    // Always an array, even with a single audience.
    expect(Array.isArray(payload.aud)).toBe(true);
    expect(payload.aud).toEqual(['citypass']);
    // Service tokens carry no human identity.
    expect(payload.roles).toBeUndefined();
    expect(payload.preferred_username).toBeUndefined();
    expect(String(payload.jti)).toMatch(/^svc-/);
  });

  it('sets the RFC 6749 §5.1 cache headers', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: { grant_type: 'client_credentials' },
    });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('pragma')).toBe('no-cache');
  });

  it('accepts percent-encoded Basic credentials (RFC 6749 §2.3.1)', async () => {
    // The id has nothing to encode, so this also proves a non-encoded value
    // still round-trips through the decoder.
    const encoded = Buffer.from(
      `${encodeURIComponent(SERVICE_CLIENT)}:${encodeURIComponent(SERVICE_SECRET)}`,
    ).toString('base64');
    const res = await tokenRequest({
      headers: { authorization: `Basic ${encoded}` },
      form: { grant_type: 'client_credentials' },
    });
    expect(res.status).toBe(200);
  });
});

describe('POST /oauth/token -- grant_type', () => {
  it('rejects a missing grant_type with invalid_request', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: {},
    });
    expect(res.status).toBe(400);
    expect((await jsonBody(res)).error).toBe('invalid_request');
  });

  it('rejects an empty grant_type with invalid_request', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: { grant_type: '' },
    });
    expect(res.status).toBe(400);
    expect((await jsonBody(res)).error).toBe('invalid_request');
  });

  it('rejects another grant with unsupported_grant_type', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: { grant_type: 'password', username: 'ana', password: 'x' },
    });
    expect(res.status).toBe(400);
    const body = await jsonBody(res);
    expect(body.error).toBe('unsupported_grant_type');
    expect(typeof body.error_description).toBe('string');
  });
});

describe('POST /oauth/token -- client authentication', () => {
  it('rejects supplying both Basic and body credentials', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, SERVICE_SECRET) },
      form: {
        grant_type: 'client_credentials',
        client_id: SERVICE_CLIENT,
        client_secret: SERVICE_SECRET,
      },
    });
    expect(res.status).toBe(400);
    expect((await jsonBody(res)).error).toBe('invalid_request');
  });

  it('rejects a request with no credentials at all with 401 invalid_client', async () => {
    const res = await tokenRequest({ form: { grant_type: 'client_credentials' } });
    expect(res.status).toBe(401);
    expect((await jsonBody(res)).error).toBe('invalid_client');
    expect(res.headers.get('www-authenticate')).toBe('Basic realm="citypass-idp"');
  });

  it('rejects a wrong secret with 401 invalid_client and a challenge', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, 'not-the-secret') },
      form: { grant_type: 'client_credentials' },
    });
    expect(res.status).toBe(401);
    expect((await jsonBody(res)).error).toBe('invalid_client');
    expect(res.headers.get('www-authenticate')).toBe('Basic realm="citypass-idp"');
    expect(res.headers.get('cache-control')).toBe('no-store');
  });

  it('makes an unknown client indistinguishable from a wrong secret', async () => {
    const unknown = await tokenRequest({
      headers: { authorization: basic('no-such-client', SERVICE_SECRET) },
      form: { grant_type: 'client_credentials' },
    });
    const wrongSecret = await tokenRequest({
      headers: { authorization: basic(SERVICE_CLIENT, 'not-the-secret') },
      form: { grant_type: 'client_credentials' },
    });

    // Identical status AND identical body: anything else enumerates the registry.
    expect(unknown.status).toBe(wrongSecret.status);
    expect(unknown.status).toBe(401);
    expect(await jsonBody(unknown)).toEqual(await jsonBody(wrongSecret));
  });

  it('refuses a human-only client whose secretHash is null', async () => {
    const res = await tokenRequest({
      headers: { authorization: basic(HUMAN_CLIENT, 'anything') },
      form: { grant_type: 'client_credentials' },
    });
    expect(res.status).toBe(401);
    expect((await jsonBody(res)).error).toBe('invalid_client');
    expect(res.headers.get('www-authenticate')).toBe('Basic realm="citypass-idp"');
  });

  it('refuses a human-only client even with an empty secret', async () => {
    const res = await tokenRequest({
      form: {
        grant_type: 'client_credentials',
        client_id: HUMAN_CLIENT,
        client_secret: '',
      },
    });
    expect(res.status).toBe(401);
    expect((await jsonBody(res)).error).toBe('invalid_client');
  });

  it('ignores a non-Basic Authorization header and falls back to the body', async () => {
    const res = await tokenRequest({
      headers: { authorization: 'Bearer some.jwt.here' },
      form: {
        grant_type: 'client_credentials',
        client_id: SERVICE_CLIENT,
        client_secret: SERVICE_SECRET,
      },
    });
    expect(res.status).toBe(200);
  });

  it('rejects a malformed Basic header (no colon) as missing credentials', async () => {
    const res = await tokenRequest({
      headers: { authorization: `Basic ${Buffer.from('nocolon').toString('base64')}` },
      form: { grant_type: 'client_credentials' },
    });
    expect(res.status).toBe(401);
    expect((await jsonBody(res)).error).toBe('invalid_client');
  });
});
