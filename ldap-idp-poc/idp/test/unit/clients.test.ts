import { describe, expect, it } from 'vitest';
import { hashSecret, verifySecret } from '../../src/clients';

/**
 * The hash produced for `grupo5-secret` by the reference implementation, and
 * the exact string sitting in config/idp.json. If a refactor changes the KDF
 * parameters or the encoding, every deployed secret stops verifying -- this
 * vector is the tripwire.
 */
const KNOWN_HASH =
  'scrypt$16384$8$1$6P1qqet3/h5hPzg38RrrAQ==$X0IVYpw0BIwxw/rR3Xr4K/Sw8coZYSx/6vJWDaQWdss=';
const KNOWN_SECRET = 'grupo5-secret';

describe('hashSecret', () => {
  it('produces the documented scrypt$N$r$p$salt$hash shape', () => {
    const parts = hashSecret('hunter2').split('$');
    expect(parts).toHaveLength(6);
    expect(parts[0]).toBe('scrypt');
    expect(parts[1]).toBe('16384');
    expect(parts[2]).toBe('8');
    expect(parts[3]).toBe('1');
    // 16-byte salt and 32-byte key, base64.
    expect(Buffer.from(parts[4]!, 'base64')).toHaveLength(16);
    expect(Buffer.from(parts[5]!, 'base64')).toHaveLength(32);
  });

  it('salts randomly, so the same secret never hashes twice the same', () => {
    expect(hashSecret('hunter2')).not.toBe(hashSecret('hunter2'));
  });

  it('round-trips through verifySecret', () => {
    const stored = hashSecret('a-long-service-secret');
    expect(verifySecret(stored, 'a-long-service-secret')).toBe(true);
    expect(verifySecret(stored, 'a-long-service-secre')).toBe(false);
    expect(verifySecret(stored, 'A-long-service-secret')).toBe(false);
  });

  it('handles unicode and colon-bearing secrets', () => {
    const secret = 'pá:ss wörd$%';
    expect(verifySecret(hashSecret(secret), secret)).toBe(true);
  });
});

describe('verifySecret', () => {
  it('accepts the known-good vector from config/idp.json', () => {
    expect(verifySecret(KNOWN_HASH, KNOWN_SECRET)).toBe(true);
  });

  it('rejects the wrong secret for a valid stored hash', () => {
    expect(verifySecret(KNOWN_HASH, 'grupo5-secre')).toBe(false);
    expect(verifySecret(KNOWN_HASH, 'grupo1-secret')).toBe(false);
  });

  it('rejects a null stored hash -- the "may not use client_credentials" case', () => {
    expect(verifySecret(null, KNOWN_SECRET)).toBe(false);
  });

  it('rejects an empty presented secret', () => {
    expect(verifySecret(KNOWN_HASH, '')).toBe(false);
    expect(verifySecret(hashSecret(''), '')).toBe(false);
  });

  it.each([
    ['empty string', ''],
    ['no separators', 'garbage'],
    ['too few fields', 'scrypt$16384$8$1$6P1qqet3/h5hPzg38RrrAQ=='],
    ['too many fields', `${KNOWN_HASH}$extra`],
    ['wrong algorithm tag', KNOWN_HASH.replace('scrypt', 'bcrypt')],
    ['non-numeric N', KNOWN_HASH.replace('16384', 'sixteenk')],
    ['non-numeric r', KNOWN_HASH.replace('$8$', '$eight$')],
    ['non-numeric p', 'scrypt$16384$8$one$6P1qqet3/h5hPzg38RrrAQ==$X0IVYpw0BIwxw/rR3Xr4K/Sw8coZYSx/6vJWDaQWdss='],
    ['zero N', KNOWN_HASH.replace('16384', '0')],
    ['negative N', KNOWN_HASH.replace('16384', '-16384')],
    ['N that is not a power of two', KNOWN_HASH.replace('16384', '16385')],
    ['bad base64 salt', 'scrypt$16384$8$1$!!!not-base64!!!$X0IVYpw0BIwxw/rR3Xr4K/Sw8coZYSx/6vJWDaQWdss='],
    ['bad base64 hash', 'scrypt$16384$8$1$6P1qqet3/h5hPzg38RrrAQ==$***'],
    ['empty salt field', 'scrypt$16384$8$1$$X0IVYpw0BIwxw/rR3Xr4K/Sw8coZYSx/6vJWDaQWdss='],
    ['empty hash field', 'scrypt$16384$8$1$6P1qqet3/h5hPzg38RrrAQ==$'],
    ['truncated hash -- must not let timingSafeEqual throw', 'scrypt$16384$8$1$6P1qqet3/h5hPzg38RrrAQ==$X0IVYpw0BIww='],
    ['absurd cost parameters', 'scrypt$1073741824$8$1$6P1qqet3/h5hPzg38RrrAQ==$X0IVYpw0BIwxw/rR3Xr4K/Sw8coZYSx/6vJWDaQWdss='],
  ])('returns false without throwing for a malformed stored value: %s', (_label, stored) => {
    expect(() => verifySecret(stored, KNOWN_SECRET)).not.toThrow();
    expect(verifySecret(stored, KNOWN_SECRET)).toBe(false);
  });
});
