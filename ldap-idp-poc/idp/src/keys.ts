import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { webcrypto } from 'node:crypto';
import {
  calculateJwkThumbprint,
  exportJWK,
  exportPKCS8,
  generateKeyPair,
  importPKCS8,
  type JWK,
} from 'jose';

const ALG = 'RS256';

// Node does not expose a global CryptoKey under lib: ES2023, and jose v6
// returns WebCrypto keys. Alias it rather than pulling in the whole DOM lib.
type SigningKey = webcrypto.CryptoKey;

export interface KeyEntry {
  kid: string;
  privateKey: SigningKey;
  publicJwk: JWK;
}

export interface Keystore {
  /** Every key, so rotation is possible later without a format change. */
  jwks(): { keys: JWK[] };
  activeKid(): string;
  activeKey(): KeyEntry;
}

/**
 * `kid` is the RFC 7638 thumbprint of the public key, not a random value.
 * That makes restart-stability structural rather than lucky: the same key
 * file always yields the same kid, so a token issued before a restart still
 * matches a JWKS entry after it.
 */
async function kidFor(publicJwk: JWK): Promise<string> {
  return calculateJwkThumbprint(publicJwk, 'sha256');
}

/**
 * Generates a key ONLY if the directory has none. Generating on every boot
 * would invalidate every token ever issued -- the exact failure acceptance
 * criterion 11 exists to catch.
 */
export async function ensureKey(dir: string): Promise<string> {
  await mkdir(dir, { recursive: true });
  const existing = (await readdir(dir)).filter((f) => f.endsWith('.pem'));
  if (existing.length > 0) return join(dir, existing[0]!);

  const { privateKey, publicKey } = await generateKeyPair(ALG, {
    modulusLength: 2048,
    extractable: true,
  });
  const kid = await kidFor(await exportJWK(publicKey));
  const path = join(dir, `${kid}.pem`);
  // 0600: the private key is the root of trust for every module in the system.
  await writeFile(path, await exportPKCS8(privateKey), { mode: 0o600 });
  return path;
}

export async function loadKeystore(dir: string, activeKid?: string): Promise<Keystore> {
  await ensureKey(dir);
  const files = (await readdir(dir)).filter((f) => f.endsWith('.pem')).sort();

  const entries: KeyEntry[] = [];
  for (const file of files) {
    const pem = await readFile(join(dir, file), 'utf8');
    const privateKey = await importPKCS8(pem, ALG, { extractable: true });
    const privateJwk = await exportJWK(privateKey);
    // Strip the private components: only n and e may ever be published.
    const publicJwk: JWK = { kty: privateJwk.kty, n: privateJwk.n, e: privateJwk.e };
    const kid = await kidFor(publicJwk);
    entries.push({ kid, privateKey, publicJwk: { ...publicJwk, kid, alg: ALG, use: 'sig' } });
  }

  if (entries.length === 0) throw new Error(`No signing keys found in ${dir}`);

  const active = activeKid ? entries.find((e) => e.kid === activeKid) : entries[0];
  if (!active) throw new Error(`ACTIVE_KID ${activeKid} not present in ${dir}`);

  return {
    jwks: () => ({ keys: entries.map((e) => e.publicJwk) }),
    activeKid: () => active.kid,
    activeKey: () => active,
  };
}
