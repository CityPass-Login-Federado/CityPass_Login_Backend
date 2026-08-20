import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import { createApp, type BuiltApp } from '../../src/app';
import { loadConfigFromEnv } from '../../src/config';

/**
 * Integration tests run the app IN-PROCESS against the containerised LDAP on
 * localhost:1389. Driving the app through the container instead would mean the
 * coverage reporter never sees any of this code, and the 60% gate would be
 * measuring the wrong thing.
 */
export const LDAP_URL = process.env.TEST_LDAP_URL ?? 'ldap://localhost:1389';

export interface Harness {
  base: string;
  built: BuiltApp;
  stop(): Promise<void>;
}

export async function startHarness(overrides: Record<string, string> = {}): Promise<Harness> {
  const keysDir = await mkdtemp(join(tmpdir(), 'citypass-keys-'));
  const config = loadConfigFromEnv({
    ISSUER: 'https://idp.citypass.local',
    JWKS_URL: 'http://localhost/.well-known/jwks.json',
    KEYS_DIR: keysDir,
    TOKEN_TTL_SECONDS: '900',
    LDAP_URL,
    LDAP_BIND_DN: 'cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local',
    LDAP_BIND_PW: 'readonly-secret',
    LDAP_PEOPLE_BASE: 'ou=People,dc=citypass,dc=local',
    LDAP_CONNECT_TIMEOUT_MS: '3000',
    LDAP_TIMEOUT_MS: '5000',
    DEBUG_TOKEN_ENABLED: 'true',
    CONFIG_FILE: new URL('../../config/idp.json', import.meta.url).pathname,
    ...overrides,
  } as NodeJS.ProcessEnv);

  const built = await createApp(config);
  const server: Server = await new Promise((resolve) => {
    const s = built.app.listen(0, () => resolve(s));
  });
  const { port } = server.address() as AddressInfo;

  return {
    base: `http://127.0.0.1:${port}`,
    built,
    async stop() {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await built.close();
    },
  };
}

export async function debugToken(
  base: string,
  body: Record<string, string>,
): Promise<{ status: number; json: any }> {
  const res = await fetch(`${base}/debug/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

/** Decoding without verifying is acceptable in tests only, never in src/. */
export function payloadOf(jwt: string): any {
  return JSON.parse(Buffer.from(jwt.split('.')[1]!, 'base64url').toString('utf8'));
}

export function headerOf(jwt: string): any {
  return JSON.parse(Buffer.from(jwt.split('.')[0]!, 'base64url').toString('utf8'));
}

/** fetch().json() is typed `unknown`; tests assert on the shape themselves. */
export async function getJson(url: string): Promise<any> {
  return (await fetch(url)).json();
}
