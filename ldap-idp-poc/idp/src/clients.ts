/**
 * Client secret storage. Secrets are never kept in the clear: `idp.json` holds
 * only a scrypt hash, so a leaked config file does not hand over the ability to
 * mint service tokens.
 *
 * Stored format (a single self-describing string, so the parameters travel with
 * the hash and old hashes stay verifiable after a cost bump):
 *
 *     scrypt$<N>$<r>$<p>$<saltBase64>$<hashBase64>
 */
import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

/** CPU/memory cost. 16384 is the interactive-login figure from RFC 7914 §2. */
const N = 16384;
const R = 8;
const P = 1;
const KEY_LEN = 32;
const SALT_LEN = 16;
/**
 * Node's default maxmem is 32 MiB and N=16384, r=8 needs 128*N*r = 16 MiB, so
 * it fits only just. Raising the ceiling keeps headroom for a future cost bump
 * and for verifying hashes whose stored parameters are larger than today's --
 * without it scrypt throws "Invalid scrypt params" instead of hashing.
 */
const MAXMEM = 64 * 1024 * 1024;

const PREFIX = 'scrypt';

function derive(secret: string, salt: Buffer, n: number, r: number, p: number): Buffer {
  return scryptSync(secret, salt, KEY_LEN, { N: n, r, p, maxmem: MAXMEM });
}

/** Encodes a fresh random salt with the derived key. */
export function hashSecret(secret: string): string {
  const salt = randomBytes(SALT_LEN);
  const hash = derive(secret, salt, N, R, P);
  return [PREFIX, N, R, P, salt.toString('base64'), hash.toString('base64')].join('$');
}

/**
 * Base64 decoding in Node is permissive: it silently drops invalid characters
 * rather than throwing. Re-encoding and comparing is the only way to tell a
 * corrupted field from a valid one.
 */
function decodeBase64(value: string): Buffer | null {
  if (value === '') return null;
  const buf = Buffer.from(value, 'base64');
  if (buf.length === 0) return null;
  if (buf.toString('base64') !== value) return null;
  return buf;
}

function parsePositiveInt(value: string): number | null {
  if (!/^[0-9]+$/.test(value)) return null;
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n <= 0) return null;
  return n;
}

/**
 * Constant-time comparison against a stored hash. Returns false -- never throws
 * -- for every malformed input, so a hand-edited config file degrades into a
 * failed login rather than a 500.
 */
export function verifySecret(stored: string | null, presented: string): boolean {
  if (!stored || !presented) return false;

  const parts = stored.split('$');
  if (parts.length !== 6) return false;
  const [prefix, rawN, rawR, rawP, rawSalt, rawHash] = parts as [
    string,
    string,
    string,
    string,
    string,
    string,
  ];
  if (prefix !== PREFIX) return false;

  const n = parsePositiveInt(rawN);
  const r = parsePositiveInt(rawR);
  const p = parsePositiveInt(rawP);
  if (n === null || r === null || p === null) return false;
  // N must be a power of two greater than 1, or scrypt rejects the parameters.
  if ((n & (n - 1)) !== 0 || n < 2) return false;

  const salt = decodeBase64(rawSalt);
  const expected = decodeBase64(rawHash);
  if (!salt || !expected) return false;

  let actual: Buffer;
  try {
    actual = derive(presented, salt, n, r, p);
  } catch {
    // Absurd cost parameters in the stored string, e.g. N beyond maxmem.
    return false;
  }

  // timingSafeEqual THROWS on a length mismatch, which would both leak the
  // stored length and crash the request. Check first.
  if (actual.length !== expected.length) return false;
  return timingSafeEqual(actual, expected);
}
