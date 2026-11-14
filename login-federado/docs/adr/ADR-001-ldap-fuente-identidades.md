# ADR-001: OpenLDAP como fuente de identidad y SQL para datos operacionales

## Estado

Accepted — 14 de noviembre de 2026.

## Contexto y Problema

CityPass+ necesita identidades compartidas por múltiples módulos, autenticación sin exponer hashes, grupos por módulo y administración delegada. Al mismo tiempo, refresh tokens, intentos de login y auditoría requieren consultas transaccionales e índices temporales que no son atributos de identidad.

## Opciones Consideradas (Pros y Contras de cada una)

### Solo PostgreSQL

- Pros: modelo y transacciones conocidos; una sola tecnología.
- Contras: credenciales y grupos dejan de usar un directorio estándar; obliga a implementar bind, ACL y semántica de membresía.

### Solo OpenLDAP

- Pros: una fuente central y estándar para personas, contraseñas y grupos.
- Contras: refresh chains, ventanas temporales y auditoría son más difíciles de consultar, indexar y actualizar de manera transaccional.

### OpenLDAP + PostgreSQL

- Pros: separa identidad durable de estado operacional; permite bind LDAP y consultas SQL eficientes.
- Contras: dos almacenes, referencias lógicas sin FK y operaciones LDAP/SQL no atómicas.

### Proveedor OIDC externo

- Pros: protocolos y capacidades administradas, como MFA y federación.
- Contras: costo/dependencia, menor control académico y migración del modelo LDAP exigido.

## Decisión Tomada y Justificación

Adoptar OpenLDAP para `inetOrgPerson`, contraseñas, estado de cuenta y `groupOfNames`; usar PostgreSQL para `refresh_tokens`, `login_attempts` y `panel_audit`. El login busca globalmente por `uid` y autentica por bind. Dos cuentas de servicio separan lectura (`readonly`) y escritura administrativa (`panel-writer`).

La separación está implementada en `LdapDirectory`, `PanelDirectoryService`, las ACL/overlays de `ldap/config/apply-config.sh` y las entidades JPA.

## Consecuencias Técnicas

- `employeeNumber` es el `sub` estable y vincula lógicamente SQL con LDAP.
- No existen FK entre almacenes; las inconsistencias deben detectarse en servicios.
- El overlay `unique` protege `uid`, `mail` y `employeeNumber`; `memberOf`, `refint`, `constraint` y `ppolicy` aplican reglas del directorio.
- Mutación LDAP y auditoría SQL no comparten una transacción distribuida.
- Backups, TLS, rotación de secretos y alta disponibilidad deben cubrir dos tecnologías.
