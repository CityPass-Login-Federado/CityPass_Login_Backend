import { readFileSync } from 'node:fs';

/**
 * EXTENSION POINT (prod): in production both of these tables live in Postgres.
 * Everything above this interface is source-agnostic -- swapping the file for
 * a database means writing a second implementation of ConfigStore and changing
 * one line in createApp(). Nothing else in the codebase touches the file.
 */
export interface ConfigStore {
  getClient(clientId: string): ClientRecord | undefined;
  /**
   * Translate LDAP group DNs into public roles for one audience.
   * A group with no mapping entry is ignored. Roles are NEVER derived by
   * parsing the CN: creating a similarly named group must not grant anything.
   */
  getRolesForGroups(groupDns: string[], audience: string): string[];
}

export interface RoleMapping {
  role: string;
  audience: string;
}

export interface ClientRecord {
  clientId: string;
  /**
   * Audiences this client is allowed to request for HUMAN tokens.
   * Without this the audience filter is bypassable: any caller could simply
   * ask for another module's audience.
   */
  audiences: string[];
  /** Audience placed in SERVICE tokens issued to this client. */
  serviceAudience: string[];
  /** Event-bus namespace. Service tokens only. */
  namespace?: string;
  /** scrypt hash, or null when the client may not use client_credentials. */
  secretHash: string | null;
}

interface RawConfig {
  roles: Record<string, RoleMapping>;
  clients: Record<string, Omit<ClientRecord, 'clientId'>>;
}

/**
 * DNs are compared after normalisation, never as raw strings. OpenLDAP is free
 * to hand back `CN=x, OU=y` with different case and spacing than the mapping
 * file uses, and a case-sensitive compare would silently drop roles.
 */
export function normalizeDn(dn: string): string {
  return dn
    .split(',')
    .map((part) => part.trim())
    .join(',')
    .toLowerCase();
}

export class FileConfigStore implements ConfigStore {
  private readonly roles = new Map<string, RoleMapping>();
  private readonly clients = new Map<string, ClientRecord>();

  constructor(raw: RawConfig) {
    for (const [dn, mapping] of Object.entries(raw.roles)) {
      this.roles.set(normalizeDn(dn), mapping);
    }
    for (const [clientId, rest] of Object.entries(raw.clients)) {
      this.clients.set(clientId, { clientId, ...rest });
    }
  }

  static fromFile(path: string): FileConfigStore {
    return new FileConfigStore(JSON.parse(readFileSync(path, 'utf8')) as RawConfig);
  }

  getClient(clientId: string): ClientRecord | undefined {
    return this.clients.get(clientId);
  }

  getRolesForGroups(groupDns: string[], audience: string): string[] {
    const out = new Set<string>();
    for (const dn of groupDns) {
      const mapping = this.roles.get(normalizeDn(dn));
      if (mapping && mapping.audience === audience) out.add(mapping.role);
    }
    return [...out].sort();
  }
}
