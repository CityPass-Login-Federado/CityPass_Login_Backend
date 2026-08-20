import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { createRemoteJWKSet, jwtVerify } from 'jose';

const exec = promisify(execFile);
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const IDP = process.env.TEST_IDP_URL ?? 'http://localhost:8080';
const CONTAINER = 'citypass-idp';

/**
 * Acceptance criterion 11: a token issued BEFORE the IdP restarts must still
 * validate afterwards.
 *
 * This test deliberately drives the real container rather than an in-process
 * app. The failure mode it exists to catch is configuration, not code: a
 * missing idp-keys volume in docker-compose.yml, a mount path that does not
 * match KEYS_DIR, or an entrypoint that writes the key somewhere outside the
 * volume. A temp-directory test passes happily through all three.
 *
 * It uses /oauth/token (always on) rather than /debug/token (off by default),
 * so no .env file is needed for it to run.
 *
 * It restarts a shared container, so it MUST NOT run concurrently with the
 * other integration files -- see fileParallelism: false in vitest.config.ts.
 */

async function healthStatus(): Promise<string> {
  try {
    const { stdout } = await exec('docker', [
      'inspect', '--format', '{{.State.Health.Status}}', CONTAINER,
    ]);
    return stdout.trim();
  } catch {
    return 'absent';
  }
}

/** Polls the container healthcheck. No fixed sleeps: those are flaky by design. */
async function waitHealthy(deadlineMs = 60_000): Promise<void> {
  const started = Date.now();
  let last = 'unknown';
  while (Date.now() - started < deadlineMs) {
    last = await healthStatus();
    if (last === 'healthy') return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(
    `${CONTAINER} did not become healthy within ${deadlineMs}ms. Last observed state: "${last}". ` +
    `Run \`docker compose logs idp\` in ${REPO_ROOT}.`,
  );
}

async function serviceToken(): Promise<string> {
  const res = await fetch(`${IDP}/oauth/token`, {
    method: 'POST',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      authorization: `Basic ${Buffer.from('grupo5:grupo5-secret').toString('base64')}`,
    },
    body: new URLSearchParams({ grant_type: 'client_credentials' }),
  });
  expect(res.status).toBe(200);
  return ((await res.json()) as any).access_token;
}

describe('acceptance 11: a token survives an IdP restart', () => {
  beforeAll(async () => {
    const status = await healthStatus();
    if (status === 'absent') {
      throw new Error(
        `Container ${CONTAINER} is not running. This test needs the full stack: ` +
        `run \`docker compose up -d --build\` in ${REPO_ROOT} first.`,
      );
    }
    await waitHealthy();
  });

  it('re-verifies a pre-restart token against the freshly served JWKS', async () => {
    const token = await serviceToken();
    const kidBefore = JSON.parse(
      Buffer.from(token.split('.')[0]!, 'base64url').toString('utf8'),
    ).kid;
    const jwksBefore: any = await (await fetch(`${IDP}/.well-known/jwks.json`)).json();

    // --force-recreate, NOT `restart`. `docker compose restart` reuses the
    // same container and therefore the same writable layer, so a key written
    // outside /keys would still be there afterwards and the test would pass
    // on a broken configuration. Recreating the container throws the writable
    // layer away, leaving only the idp-keys volume -- which is the thing
    // actually under test.
    await exec('docker', ['compose', 'up', '-d', '--force-recreate', 'idp'], { cwd: REPO_ROOT });
    await waitHealthy();

    const jwksAfter: any = await (await fetch(`${IDP}/.well-known/jwks.json`)).json();

    // The key was loaded from the volume, not regenerated: same kid, same
    // modulus. If the volume were missing, both would change.
    expect(jwksAfter.keys.map((k: any) => k.kid)).toEqual(jwksBefore.keys.map((k: any) => k.kid));
    expect(jwksAfter.keys[0].n).toBe(jwksBefore.keys[0].n);
    expect(jwksAfter.keys.map((k: any) => k.kid)).toContain(kidBefore);

    // The real assertion: the OLD token verifies against the NEW JWKS.
    // jose is used directly here rather than the project verifier so that a
    // failure here can only mean key persistence broke.
    const jwks = createRemoteJWKSet(new URL(`${IDP}/.well-known/jwks.json`));
    const { payload } = await jwtVerify(token, jwks, {
      algorithms: ['RS256'],
      issuer: 'https://idp.citypass.local',
      audience: 'citypass',
    });
    expect(payload.token_use).toBe('service');
    expect(payload.sub).toBe('grupo5');
  });
});
