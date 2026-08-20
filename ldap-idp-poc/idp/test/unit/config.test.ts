import { describe, expect, it } from 'vitest';
import { loadConfigFromEnv } from '../../src/config';

const BASE = { KEYS_DIR: '/keys', CONFIG_FILE: '/app/config/idp.json' } as NodeJS.ProcessEnv;

describe('loadConfigFromEnv', () => {
  it('applies working defaults', () => {
    const c = loadConfigFromEnv({ ...BASE });
    expect(c.port).toBe(8080);
    expect(c.issuer).toBe('https://idp.citypass.local');
    expect(c.tokenTtlSeconds).toBe(900);
    // The PoC-only endpoint is off unless explicitly enabled.
    expect(c.debugTokenEnabled).toBe(false);
  });

  it('only enables the debug endpoint for the exact string "true"', () => {
    for (const v of ['false', 'TRUE', '1', 'yes', '']) {
      expect(loadConfigFromEnv({ ...BASE, DEBUG_TOKEN_ENABLED: v }).debugTokenEnabled).toBe(false);
    }
    expect(loadConfigFromEnv({ ...BASE, DEBUG_TOKEN_ENABLED: 'true' }).debugTokenEnabled).toBe(true);
  });

  it('enforces the contracted 10-15 minute TTL window', () => {
    expect(() => loadConfigFromEnv({ ...BASE, TOKEN_TTL_SECONDS: '60' })).toThrow(/between 600 and 900/);
    expect(() => loadConfigFromEnv({ ...BASE, TOKEN_TTL_SECONDS: '86400' })).toThrow(/between 600 and 900/);
    expect(loadConfigFromEnv({ ...BASE, TOKEN_TTL_SECONDS: '600' }).tokenTtlSeconds).toBe(600);
  });

  it('rejects nonsense integers rather than coercing them', () => {
    expect(() => loadConfigFromEnv({ ...BASE, PORT: 'eight' })).toThrow(/positive integer/);
    expect(() => loadConfigFromEnv({ ...BASE, PORT: '-1' })).toThrow(/positive integer/);
  });

  it('rejects an explicitly empty required value rather than silently using it', () => {
    // `??` deliberately does not treat '' as absent: an empty ISSUER in a
    // deployment is a mistake, not a request for the default.
    expect(() => loadConfigFromEnv({ ...BASE, ISSUER: '' })).toThrow(/ISSUER/);
    expect(() => loadConfigFromEnv({ ...BASE, KEYS_DIR: '' })).toThrow(/KEYS_DIR/);
  });
});
