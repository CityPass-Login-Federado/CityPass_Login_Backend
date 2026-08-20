import { describe, expect, it } from 'vitest';
import { FileConfigStore, normalizeDn } from '../../src/store';

const CONFIG = new URL('../../config/idp.json', import.meta.url).pathname;
const AGENTE = 'cn=app-reclamos-agente,ou=Reclamos,ou=Groups,dc=citypass,dc=local';
const AUDITOR = 'cn=app-reclamos-auditor,ou=Reclamos,ou=Groups,dc=citypass,dc=local';
const CONSULTA = 'cn=app-movilidad-consulta,ou=Movilidad,ou=Groups,dc=citypass,dc=local';

describe('normalizeDn', () => {
  it('collapses case and RDN spacing so directory formatting cannot drop roles', () => {
    expect(normalizeDn('CN=X, OU=Y ,DC=z')).toBe('cn=x,ou=y,dc=z');
  });
});

describe('FileConfigStore', () => {
  const store = FileConfigStore.fromFile(CONFIG);

  it('maps a mapped group to its role for the matching audience', () => {
    expect(store.getRolesForGroups([AGENTE], 'citypass-reclamos-api')).toEqual(['reclamos:agente']);
  });

  it('ignores a group with no mapping entry', () => {
    expect(store.getRolesForGroups([AUDITOR], 'citypass-reclamos-api')).toEqual([]);
  });

  it('does not derive roles by parsing the CN', () => {
    // A group that follows the naming convention perfectly but was never
    // registered must grant nothing.
    const invented = 'cn=app-reclamos-admin,ou=Reclamos,ou=Groups,dc=citypass,dc=local';
    expect(store.getRolesForGroups([invented], 'citypass-reclamos-api')).toEqual([]);
  });

  it('filters by audience', () => {
    expect(store.getRolesForGroups([AGENTE, CONSULTA], 'citypass-reclamos-api')).toEqual(['reclamos:agente']);
    expect(store.getRolesForGroups([AGENTE, CONSULTA], 'citypass-movilidad-api')).toEqual(['movilidad:consulta']);
  });

  it('matches DNs case-insensitively', () => {
    expect(store.getRolesForGroups([AGENTE.toUpperCase()], 'citypass-reclamos-api')).toEqual(['reclamos:agente']);
  });

  it('returns an empty array, not undefined, when nothing matches', () => {
    expect(store.getRolesForGroups([], 'citypass-reclamos-api')).toEqual([]);
  });

  it('deduplicates roles', () => {
    expect(store.getRolesForGroups([AGENTE, AGENTE], 'citypass-reclamos-api')).toEqual(['reclamos:agente']);
  });

  it('exposes clients with their allowed audiences', () => {
    expect(store.getClient('citypass-reclamos-web')?.audiences).toEqual(['citypass-reclamos-api']);
    expect(store.getClient('grupo5')?.namespace).toBe('com.citypass.reclamos');
    expect(store.getClient('nope')).toBeUndefined();
  });

  it('never lets a web client claim a service audience', () => {
    expect(store.getClient('citypass-reclamos-web')?.serviceAudience).toEqual([]);
  });
});
