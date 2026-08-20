import { randomUUID } from 'node:crypto';
import { SignJWT } from 'jose';
import type { AppConfig } from './config';
import type { Keystore } from './keys';

/** Bumped only on a breaking change to the payload. Part of the frozen contract. */
export const TOKEN_VERSION = 1;

export interface IssuedToken {
  token: string;
  /** Seconds. Must be a NUMBER in the OAuth response, never a string. */
  expiresIn: number;
}

export interface HumanTokenInput {
  /** employeeNumber from LDAP. Never a username, never an email. */
  subject: string;
  preferredUsername: string;
  /** Always an array, even with one element. */
  audience: string[];
  /** Only the roles for the token's target audience. */
  roles: string[];
}

export interface ServiceTokenInput {
  /** The client_id. */
  subject: string;
  audience: string[];
  namespace: string;
}

async function sign(
  keystore: Keystore,
  config: AppConfig,
  payload: Record<string, unknown>,
  audience: string[],
  subject: string,
): Promise<IssuedToken> {
  const key = keystore.activeKey();
  const now = Math.floor(Date.now() / 1000);
  const token = await new SignJWT(payload)
    // kid travels in the header so a verifier can pick the right JWKS entry
    // without guessing, which is what makes rotation possible at all.
    .setProtectedHeader({ alg: 'RS256', kid: key.kid, typ: 'JWT' })
    .setIssuer(config.issuer)
    .setSubject(subject)
    .setAudience(audience)
    .setIssuedAt(now)
    .setExpirationTime(now + config.tokenTtlSeconds)
    .sign(key.privateKey);
  return { token, expiresIn: config.tokenTtlSeconds };
}

export function issueHumanToken(
  keystore: Keystore,
  config: AppConfig,
  input: HumanTokenInput,
): Promise<IssuedToken> {
  return sign(
    keystore,
    config,
    {
      token_use: 'human',
      ver: TOKEN_VERSION,
      preferred_username: input.preferredUsername,
      roles: input.roles,
      jti: randomUUID(),
    },
    input.audience,
    input.subject,
  );
}

export function issueServiceToken(
  keystore: Keystore,
  config: AppConfig,
  input: ServiceTokenInput,
): Promise<IssuedToken> {
  return sign(
    keystore,
    config,
    {
      token_use: 'service',
      ver: TOKEN_VERSION,
      namespace: input.namespace,
      // The `svc-` prefix is contractual; the rest is opaque.
      jti: `svc-${randomUUID()}`,
    },
    input.audience,
    input.subject,
  );
}
