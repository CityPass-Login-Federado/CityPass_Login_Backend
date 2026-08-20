import { Router } from 'express';
import type { AppContext } from '../context';
import { issueHumanToken } from '../tokens';

/**
 * ############################################################################
 * # POC ONLY -- THIS IS NOT THE FINAL CONTRACT.                              #
 * #                                                                          #
 * # POST /debug/token exists for exactly one reason: to validate the human   #
 * # token format end to end without first implementing Authorization Code +  #
 * # PKCE. It takes a raw password over HTTP. It must never be enabled on a   #
 * # deployed environment.                                                    #
 * #                                                                          #
 * # EXTENSION POINT (prod): delete this file and this route. The replacement #
 * # is the authorization-code + PKCE flow. Nothing else depends on it.       #
 * #                                                                          #
 * # It is mounted only when DEBUG_TOKEN_ENABLED=true (default false), so the #
 * # path 404s unless someone opts in deliberately.                           #
 * ############################################################################
 */
export function debugRouter(ctx: AppContext): Router {
  const router = Router();

  router.post('/token', async (req, res) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const clientId = typeof body['client_id'] === 'string' ? body['client_id'] : '';
    const username = typeof body['username'] === 'string' ? body['username'] : '';
    const password = typeof body['password'] === 'string' ? body['password'] : '';
    const requested = typeof body['audience'] === 'string' ? body['audience'] : undefined;

    if (!clientId) {
      return res.status(400).json({ error: 'invalid_request', error_description: 'client_id is required' });
    }

    const client = ctx.store.getClient(clientId);
    if (!client) {
      return res.status(401).json({ error: 'invalid_client' });
    }

    // Audience comes from the CLIENT REGISTRY, never from the request alone.
    // Without this, audience-scoped role filtering is bypassable: any caller
    // could simply ask for another module's audience.
    let audience: string;
    if (requested !== undefined) {
      if (!client.audiences.includes(requested)) {
        return res.status(400).json({
          error: 'invalid_request',
          error_description: 'requested audience is not allowed for this client',
        });
      }
      audience = requested;
    } else if (client.audiences.length === 1) {
      audience = client.audiences[0]!;
    } else if (client.audiences.length === 0) {
      return res.status(400).json({
        error: 'invalid_request',
        error_description: 'client may not request human tokens',
      });
    } else {
      return res.status(400).json({
        error: 'invalid_request',
        error_description: 'audience is required: this client may request more than one',
      });
    }

    // One generic failure for every authentication outcome: empty password,
    // unknown user, ambiguous user, wrong password, directory down. Anything
    // more specific is a user-enumeration oracle.
    const user = await ctx.ldap.authenticate(username, password);
    if (!user) {
      return res.status(401).json({ error: 'invalid_grant', error_description: 'authentication failed' });
    }

    // Unmapped groups fall out here: getRolesForGroups ignores any DN with no
    // entry in the mapping table. An empty result is legitimate -- the user
    // authenticated, they simply hold no roles for this audience. Authorization
    // is the resource server's job.
    const roles = ctx.store.getRolesForGroups(user.groupDns, audience);

    const { token, expiresIn } = await issueHumanToken(ctx.keystore, ctx.config, {
      subject: user.employeeNumber,
      preferredUsername: user.uid,
      audience: [audience],
      roles,
    });

    res.set('Cache-Control', 'no-store').set('Pragma', 'no-cache');
    return res.status(200).json({ access_token: token, token_type: 'Bearer', expires_in: expiresIn });
  });

  return router;
}
