/**
 * RFC 6749 §4.4 client_credentials. This endpoint issues SERVICE tokens only:
 * it never touches LDAP, because services are not people and have no groups,
 * no employeeNumber and no roles.
 */
import { Router, type Request, type Response } from 'express';
import type { AppContext } from '../context';
import { verifySecret } from '../clients';
import { issueServiceToken } from '../tokens';

const REALM = 'citypass-idp';
const GRANT_TYPE = 'client_credentials';

interface Credentials {
  clientId: string;
  clientSecret: string;
}

/** RFC 6749 §5.1: token responses must never be cached anywhere. */
function noStore(res: Response): void {
  res.set('Cache-Control', 'no-store');
  res.set('Pragma', 'no-cache');
}

function fail(res: Response, status: number, error: string, description?: string): void {
  noStore(res);
  // RFC 6749 §5.2: a 401 from the token endpoint carries a challenge.
  if (status === 401) res.set('WWW-Authenticate', `Basic realm="${REALM}"`);
  const body: Record<string, string> = { error };
  if (description) body.error_description = description;
  res.status(status).json(body);
}

/**
 * RFC 6749 §2.3.1 form-urlencodes the id and secret before base64 -- so `p@ss`
 * may arrive as `p%40ss`. Real clients are inconsistent about this, and a
 * secret containing a literal `%` is not valid percent-encoding at all, so a
 * decode failure falls back to the raw value rather than rejecting the request.
 */
function formDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function readBasicAuth(req: Request): Credentials | null {
  const header = req.get('authorization');
  if (!header) return null;
  const [scheme, ...rest] = header.split(' ');
  if (!scheme || scheme.toLowerCase() !== 'basic') return null;
  const encoded = rest.join(' ').trim();
  if (!encoded) return null;

  const decoded = Buffer.from(encoded, 'base64').toString('utf8');
  // Split on the FIRST colon only: a colon is legal inside a secret, illegal
  // inside a client_id.
  const sep = decoded.indexOf(':');
  if (sep < 0) return null;
  return {
    clientId: formDecode(decoded.slice(0, sep)),
    clientSecret: formDecode(decoded.slice(sep + 1)),
  };
}

function readBodyAuth(req: Request): Credentials | null {
  const body = (req.body ?? {}) as Record<string, unknown>;
  const clientId = typeof body.client_id === 'string' ? body.client_id : '';
  const clientSecret = typeof body.client_secret === 'string' ? body.client_secret : '';
  if (!clientId && !clientSecret) return null;
  return { clientId, clientSecret };
}

export function oauthRouter(ctx: AppContext): Router {
  const router = Router();

  router.post('/token', async (req: Request, res: Response) => {
    const body = (req.body ?? {}) as Record<string, unknown>;

    const grantType = typeof body.grant_type === 'string' ? body.grant_type : '';
    if (!grantType) {
      return fail(res, 400, 'invalid_request', 'grant_type is required');
    }
    if (grantType !== GRANT_TYPE) {
      return fail(
        res,
        400,
        'unsupported_grant_type',
        `only ${GRANT_TYPE} is supported by this endpoint`,
      );
    }

    const basic = readBasicAuth(req);
    const posted = readBodyAuth(req);
    if (basic && posted) {
      // §2.3.1: "The client MUST NOT use more than one authentication method in
      // each request." Two sets of credentials is ambiguous, not redundant.
      return fail(
        res,
        400,
        'invalid_request',
        'use either the Authorization header or the request body, not both',
      );
    }

    const credentials = basic ?? posted;
    if (!credentials || !credentials.clientId || !credentials.clientSecret) {
      return fail(res, 401, 'invalid_client', 'client authentication required');
    }

    const client = ctx.store.getClient(credentials.clientId);
    // Unknown client, wrong secret, human-only client and non-service client
    // all collapse into ONE response. Anything else lets a caller enumerate the
    // client registry with a wrong password.
    const authenticated = client ? verifySecret(client.secretHash, credentials.clientSecret) : false;
    if (!client || !authenticated) {
      return fail(res, 401, 'invalid_client', 'client authentication failed');
    }
    if (client.serviceAudience.length === 0 || !client.namespace) {
      return fail(res, 401, 'invalid_client', 'client authentication failed');
    }

    const issued = await issueServiceToken(ctx.keystore, ctx.config, {
      subject: client.clientId,
      audience: client.serviceAudience,
      namespace: client.namespace,
    });

    noStore(res);
    res.status(200).json({
      access_token: issued.token,
      token_type: 'Bearer',
      // A NUMBER. Some clients do `Date.now() + expires_in * 1000` and a string
      // turns that into concatenation.
      expires_in: issued.expiresIn,
    });
  });

  return router;
}
