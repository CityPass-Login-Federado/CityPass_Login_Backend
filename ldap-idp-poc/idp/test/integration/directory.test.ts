import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Client, EqualityFilter } from 'ldapts';
import { LDAP_URL } from './helpers';

const RO = 'cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local';
const PEOPLE = 'ou=People,dc=citypass,dc=local';

let client: Client;
beforeAll(async () => {
  client = new Client({ url: LDAP_URL, connectTimeout: 3000, timeout: 5000 });
  await client.bind(RO, 'readonly-secret');
});
afterAll(async () => { await client?.unbind().catch(() => undefined); });

/**
 * R18 regression. The slapd postinst leaves a default database behind in
 * /var/lib/ldap; if the image does not wipe it, Docker copies it into the
 * named volume on first mount and slapd serves Debian's default suffix. The
 * server still works, so nothing fails loudly -- only this assertion catches
 * it. Exact match, deliberately: "contains" would pass with both suffixes
 * present, which is precisely the broken state.
 */
describe('R18: the directory serves exactly one naming context, and it is ours', () => {
  it('rootDSE namingContexts is exactly dc=citypass,dc=local', async () => {
    const anon = new Client({ url: LDAP_URL, connectTimeout: 3000, timeout: 5000 });
    try {
      const { searchEntries } = await anon.search('', {
        scope: 'base',
        filter: '(objectClass=*)',
        attributes: ['namingContexts'],
      });
      expect(searchEntries).toHaveLength(1);
      const raw = (searchEntries[0] as any).namingContexts;
      const contexts = Array.isArray(raw) ? raw.map(String) : [String(raw)];
      expect(contexts).toEqual(['dc=citypass,dc=local']);
    } finally {
      await anon.unbind().catch(() => undefined);
    }
  });
});

describe('the seed produces the memberships the IdP depends on', () => {
  it('populates memberOf, which slapadd would have skipped silently', async () => {
    const { searchEntries } = await client.search(PEOPLE, {
      scope: 'sub',
      filter: new EqualityFilter({ attribute: 'uid', value: 'mgomez' }),
      attributes: ['memberOf', 'employeeNumber'],
    });
    expect(searchEntries).toHaveLength(1);
    const e = searchEntries[0] as any;
    const groups = (Array.isArray(e.memberOf) ? e.memberOf : [e.memberOf]).map(String);
    expect(groups).toHaveLength(2);
    expect(groups).toContain('cn=app-reclamos-supervisor,ou=Reclamos,ou=Groups,dc=citypass,dc=local');
    expect(groups).toContain('cn=app-movilidad-consulta,ou=Movilidad,ou=Groups,dc=citypass,dc=local');
  });

  it('uses deterministic employeeNumbers, so rebuilding never changes a subject', async () => {
    const expected: Record<string, string> = {
      jperez: 'CP-8f7d2c10',
      mgomez: 'CP-4a1e9b73',
      lrossi: 'CP-2c6d0f45',
    };
    for (const [uid, employeeNumber] of Object.entries(expected)) {
      const { searchEntries } = await client.search(PEOPLE, {
        scope: 'sub',
        filter: new EqualityFilter({ attribute: 'uid', value: uid }),
        attributes: ['employeeNumber'],
      });
      expect(String((searchEntries[0] as any).employeeNumber)).toBe(employeeNumber);
    }
  });

  it('keeps the empty group empty of humans (groupOfNames needs one member)', async () => {
    const { searchEntries } = await client.search('ou=Movilidad,ou=Groups,dc=citypass,dc=local', {
      scope: 'sub',
      filter: new EqualityFilter({ attribute: 'cn', value: 'app-movilidad-supervisor' }),
      attributes: ['member'],
    });
    const members = [(searchEntries[0] as any).member].flat().map(String);
    expect(members).toEqual(['cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local']);
  });

  it('keeps service accounts out of the human search base', async () => {
    for (const cn of ['readonly', 'idp-admin', 'empty-group-placeholder']) {
      const { searchEntries } = await client.search(PEOPLE, {
        scope: 'sub',
        filter: new EqualityFilter({ attribute: 'cn', value: cn }),
      });
      expect(searchEntries).toHaveLength(0);
    }
  });

  it('gives the placeholder and idp-admin no password to bind with', async () => {
    for (const dn of [
      'cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local',
      'cn=idp-admin,ou=ServiceAccounts,dc=citypass,dc=local',
    ]) {
      const probe = new Client({ url: LDAP_URL, connectTimeout: 3000, timeout: 5000 });
      await expect(probe.bind(dn, 'anything')).rejects.toThrow();
      await probe.unbind().catch(() => undefined);
    }
  });
});
