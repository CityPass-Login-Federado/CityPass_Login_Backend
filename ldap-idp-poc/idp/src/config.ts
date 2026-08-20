/**
 * Environment parsing. Fails fast and loudly at boot rather than at the first
 * request: a mistyped LDAP_URL should not first surface as a login failure.
 */
export interface AppConfig {
  port: number;
  /** JWT `iss`. A bare string -- nothing resolves it, see PROD-DELTA. */
  issuer: string;
  /** Where the verifier fetches keys. Deliberately separate from `issuer`. */
  jwksUrl: string;
  keysDir: string;
  /** Which key signs. Defaults to the only key present. */
  activeKid?: string;
  tokenTtlSeconds: number;
  ldapUrl: string;
  ldapBindDn: string;
  ldapBindPw: string;
  ldapPeopleBase: string;
  ldapConnectTimeoutMs: number;
  ldapTimeoutMs: number;
  /** PoC-only /debug/token. Off unless explicitly turned on. */
  debugTokenEnabled: boolean;
  /** Path to the roles + clients file (the future Postgres tables). */
  configFile: string;
}

function required(env: NodeJS.ProcessEnv, key: string, fallback?: string): string {
  const v = env[key] ?? fallback;
  if (v === undefined || v === '') throw new Error(`Missing required env var ${key}`);
  return v;
}

function intVar(env: NodeJS.ProcessEnv, key: string, fallback: number): number {
  const raw = env[key];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n <= 0) throw new Error(`Env var ${key} must be a positive integer, got ${raw}`);
  return n;
}

export function loadConfigFromEnv(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const ttl = intVar(env, 'TOKEN_TTL_SECONDS', 900);
  // The spec fixes the TTL window at 10-15 minutes. Enforce it here so a
  // stray value cannot quietly widen the blast radius of a leaked token.
  if (ttl < 600 || ttl > 900) {
    throw new Error(`TOKEN_TTL_SECONDS must be between 600 and 900 (10-15 min), got ${ttl}`);
  }
  return {
    port: intVar(env, 'PORT', 8080),
    issuer: required(env, 'ISSUER', 'https://idp.citypass.local'),
    jwksUrl: required(env, 'JWKS_URL', 'http://localhost:8080/.well-known/jwks.json'),
    keysDir: required(env, 'KEYS_DIR', '/keys'),
    activeKid: env.ACTIVE_KID || undefined,
    tokenTtlSeconds: ttl,
    ldapUrl: required(env, 'LDAP_URL', 'ldap://localhost:389'),
    ldapBindDn: required(env, 'LDAP_BIND_DN', 'cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local'),
    ldapBindPw: required(env, 'LDAP_BIND_PW', 'readonly-secret'),
    ldapPeopleBase: required(env, 'LDAP_PEOPLE_BASE', 'ou=People,dc=citypass,dc=local'),
    ldapConnectTimeoutMs: intVar(env, 'LDAP_CONNECT_TIMEOUT_MS', 3000),
    ldapTimeoutMs: intVar(env, 'LDAP_TIMEOUT_MS', 5000),
    debugTokenEnabled: env.DEBUG_TOKEN_ENABLED === 'true',
    configFile: required(env, 'CONFIG_FILE', new URL('../config/idp.json', import.meta.url).pathname),
  };
}
