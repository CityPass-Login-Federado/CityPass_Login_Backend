import express, { type Express } from 'express';
import type { AppConfig } from './config';
import type { AppContext } from './context';
import { LdaptsService } from './ldap';
import { loadKeystore } from './keys';
import { FileConfigStore } from './store';
import { debugRouter } from './routes/debug';
import { oauthRouter } from './routes/oauth';

export interface BuiltApp {
  app: Express;
  ctx: AppContext;
  /** Releases the long-lived LDAP connection. Tests must call this. */
  close(): Promise<void>;
}

export async function createApp(config: AppConfig): Promise<BuiltApp> {
  const ctx: AppContext = {
    config,
    // EXTENSION POINT (prod): swap FileConfigStore for a Postgres-backed
    // ConfigStore. This line is the entire migration surface.
    store: FileConfigStore.fromFile(config.configFile),
    keystore: await loadKeystore(config.keysDir, config.activeKid),
    ldap: new LdaptsService(config),
  };

  const app = express();
  app.disable('x-powered-by');
  app.use(express.json());
  // /oauth/token credentials may arrive in a form body, per the agreed contract.
  app.use(express.urlencoded({ extended: false }));

  // Public, no auth. Publishes EVERY key, not just the active one, so that
  // rotation is possible later without changing the document shape.
  app.get('/.well-known/jwks.json', (_req, res) => {
    res.set('Cache-Control', 'public, max-age=300').json(ctx.keystore.jwks());
  });

  app.get('/healthz', async (_req, res) => {
    const ldapOk = await ctx.ldap.health();
    res.status(ldapOk ? 200 : 503).json({
      status: ldapOk ? 'ok' : 'degraded',
      ldap: ldapOk ? 'ok' : 'unreachable',
      kid: ctx.keystore.activeKid(),
    });
  });

  app.use('/oauth', oauthRouter(ctx));

  if (config.debugTokenEnabled) {
    // eslint-disable-next-line no-console
    console.warn('[idp] POC ONLY: /debug/token is ENABLED. Never do this outside local development.');
    app.use('/debug', debugRouter(ctx));
  }

  return { app, ctx, close: () => ctx.ldap.close() };
}
