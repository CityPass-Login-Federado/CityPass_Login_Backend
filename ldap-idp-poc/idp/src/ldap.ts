import { Client, EqualityFilter } from 'ldapts';
import type { AppConfig } from './config';

export interface LdapUser {
  dn: string;
  uid: string;
  /** The stable identifier. Becomes the JWT `sub`. */
  employeeNumber: string;
  /** Raw group DNs from memberOf. Translated to roles by the ConfigStore. */
  groupDns: string[];
}

export interface LdapService {
  /** Returns null for EVERY failure mode. Callers must not learn which. */
  authenticate(username: string, password: string): Promise<LdapUser | null>;
  health(): Promise<boolean>;
  close(): Promise<void>;
}

/**
 * Usernames are whitelisted before they are used at all. The LDAP filter is
 * built from an EqualityFilter object (which escapes per RFC 4515), so this
 * is defence in depth rather than the only defence -- but it also keeps
 * nonsense out of the logs.
 */
const USERNAME_RE = /^[A-Za-z0-9._-]{1,64}$/;

function toArray(v: unknown): string[] {
  if (Array.isArray(v)) return v.map(String);
  if (typeof v === 'string' && v.length > 0) return [v];
  if (Buffer.isBuffer(v)) return [v.toString('utf8')];
  return [];
}

function first(v: unknown): string | undefined {
  const a = toArray(v);
  return a[0];
}

export class LdaptsService implements LdapService {
  private readonly config: AppConfig;
  private readonly: Client | undefined;

  constructor(config: AppConfig) {
    this.config = config;
  }

  private newClient(): Client {
    // EXTENSION POINT (prod): TLS. On the VPS this becomes ldaps:// (or
    // StartTLS) and gains `tlsOptions: { ca: [readFileSync(config.ldapTlsCa)] }`.
    // Note Debian 13 switched slapd/libldap from GnuTLS to OpenSSL, and with
    // no CA configured the system trust store is now loaded automatically --
    // so trusted CAs must be named explicitly. See docs/PROD-DELTA.md.
    return new Client({
      url: this.config.ldapUrl,
      // Without explicit timeouts a down LDAP hangs every login instead of
      // failing fast.
      connectTimeout: this.config.ldapConnectTimeoutMs,
      timeout: this.config.ldapTimeoutMs,
    });
  }

  /**
   * One long-lived search connection, rebound on demand. The alternative --
   * a fresh bind per lookup and per healthcheck tick -- turns connection
   * churn into the thing that makes LDAP look slow.
   */
  private async readonlyClient(): Promise<Client> {
    if (this.readonly?.isConnected) return this.readonly;
    const client = this.newClient();
    await client.bind(this.config.ldapBindDn, this.config.ldapBindPw);
    this.readonly = client;
    return client;
  }

  async authenticate(username: string, password: string): Promise<LdapUser | null> {
    // An empty password MUST be rejected before the bind is attempted: LDAP
    // treats bind-with-empty-password as an unauthenticated bind and returns
    // SUCCESS. Reaching the bind at all is the bug.
    if (typeof password !== 'string' || password.length === 0) return null;
    if (typeof username !== 'string' || !USERNAME_RE.test(username)) return null;

    let entry: Record<string, unknown> | undefined;
    try {
      const client = await this.readonlyClient();
      const { searchEntries } = await client.search(this.config.ldapPeopleBase, {
        scope: 'sub',
        // Never string concatenation. EqualityFilter escapes the value for
        // LDAP filter context (RFC 4515).
        filter: new EqualityFilter({ attribute: 'uid', value: username }),
        attributes: [
          'dn',
          'uid',
          'employeeNumber',
          // memberOf is an OPERATIONAL attribute: omit it here and the search
          // succeeds while returning no groups at all.
          'memberOf',
        ],
      });
      // Anything other than exactly one hit is a generic failure. "Not found"
      // and "ambiguous" are deliberately indistinguishable to the caller.
      if (searchEntries.length !== 1) return null;
      entry = searchEntries[0] as unknown as Record<string, unknown>;
    } catch {
      // Fail closed. Note this swallows a directory outage and a programming
      // error into the same generic null the caller gets for a wrong password.
      // That is right for the RESPONSE (no enumeration oracle) and wrong for
      // OPERATIONS: there is no logging here yet, so an LDAP outage is
      // indistinguishable from users mistyping passwords.
      // ponytail: add structured auth logging before this is deployed.
      // See docs/BUILD-REPORT.md "What is weakest".
      this.readonly = undefined;
      return null;
    }

    const dn = String(entry['dn'] ?? '');
    const employeeNumber = first(entry['employeeNumber']);
    // No employeeNumber means no stable subject. Falling back to uid would put
    // a renameable identifier in `sub`, so this is a hard failure instead.
    if (!dn || !employeeNumber) return null;

    // Separate, ephemeral connection: bind -> result -> unbind. It is never
    // reused, and nothing is read over it.
    const probe = this.newClient();
    try {
      await probe.bind(dn, password);
    } catch {
      return null;
    } finally {
      await probe.unbind().catch(() => undefined);
    }

    return {
      dn,
      uid: first(entry['uid']) ?? username,
      employeeNumber,
      groupDns: toArray(entry['memberOf']),
    };
  }

  async health(): Promise<boolean> {
    try {
      const client = await this.readonlyClient();
      // Trivial search against the directory, as required. Base-scoped on the
      // rootDSE so it stays cheap.
      await client.search('', { scope: 'base', filter: '(objectClass=*)', attributes: ['namingContexts'] });
      return true;
    } catch {
      this.readonly = undefined;
      return false;
    }
  }

  async close(): Promise<void> {
    await this.readonly?.unbind().catch(() => undefined);
    this.readonly = undefined;
  }
}
