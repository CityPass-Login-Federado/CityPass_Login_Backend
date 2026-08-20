import type { AppConfig } from './config';
import type { Keystore } from './keys';
import type { LdapService } from './ldap';
import type { ConfigStore } from './store';

/** Everything a route handler needs. Passed explicitly; no globals. */
export interface AppContext {
  config: AppConfig;
  store: ConfigStore;
  keystore: Keystore;
  ldap: LdapService;
}
