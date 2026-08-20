import { loadConfigFromEnv } from './config';
import { createApp } from './app';

const config = loadConfigFromEnv();
const { app } = await createApp(config);

app.listen(config.port, () => {
  // eslint-disable-next-line no-console
  console.log(`[idp] listening on :${config.port} iss=${config.issuer} debug=${config.debugTokenEnabled}`);
});
