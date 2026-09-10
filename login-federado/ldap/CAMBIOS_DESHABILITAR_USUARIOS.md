# Cambios para deshabilitar usuarios

## Objetivo

Permitir que el panel deshabilite una persona sin borrarla del directorio LDAP.
Una persona deshabilitada conserva sus datos, grupos e historial, pero no puede
iniciar sesion ni renovar sesiones. Para el cliente, el rechazo es generico y
responde `401`.

## Como funciona la baja

El panel modifica el atributo operacional LDAP:

```text
pwdAccountLockedTime: 000001010000Z
```

Ese valor representa un bloqueo permanente en ppolicy. La baja se realiza en
`PanelDirectoryService.disablePerson()` y la rehabilitacion elimina el atributo
en `PanelDirectoryService.enablePerson()`.

La ficha LDAP no se borra.

## Cambios en OpenLDAP

### Esquema ppolicy

La imagen `osixia/openldap:1.5.0` ya trae parte del esquema ppolicy. Intentar
cargar otra vez el esquema completo provocaba:

```text
Duplicate attributeType: 1.3.6.1.4.1.42.2.27.8.1.1
```

Por eso `ldap/Dockerfile` agrega al esquema existente los atributos
operacionales de ppolicy, especialmente `pwdAccountLockedTime`, antes de que
OpenLDAP arranque.

Esto es necesario porque los atributos con `USAGE directoryOperation` no se
pueden agregar de forma segura despues de que el esquema ya fue cargado.

### Overlay ppolicy

`ldap/config/apply-config.sh` carga:

- el modulo `ppolicy`;
- el overlay `ppolicy`;
- la politica `cn=default,ou=Policies,dc=citypass,dc=local`;
- `olcPPolicyHashCleartext: TRUE` para los cambios de contrasena enviados al
  servidor.

La politica se carga con `cn=admin,dc=citypass,dc=local`, porque pertenece al
arbol de datos. El usuario `cn=admin,cn=config` solo administra la
configuracion de OpenLDAP.

### Cuenta de escritura del panel

El backend usa:

```text
cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local
```

La ACL le permite modificar el arbol de personas y grupos, sin permitirle leer
los hashes de `userPassword`.

## Cambios en el script de configuracion

`ldap/config/apply-config.sh` fue ajustado para:

1. detectar el DN real de la base MDB;
2. detectar el DN numerado de la lista de modulos, por ejemplo
   `cn=module{0},cn=config`;
3. cargar de forma idempotente los modulos y overlays;
4. declarar los `objectClass` especificos de `unique`, `constraint` y
   `ppolicy`;
5. cargar la politica de contrasenas con el administrador del arbol de datos;
6. aplicar ACLs, indices y seed despues de los overlays;
7. detener el bootstrap ante errores reales, en lugar de ocultarlos como
   "ya aplicado".

El job `ldap-config` debe terminar con:

```text
Exited (0)
```

y el log debe terminar con:

```text
== Directorio configurado correctamente ==
```

## Cambios en el backend Java

En `src/main/java/citypass/loginfederado/identity/LdapDirectory.java`:

- `PERSON_ATTRIBUTES` solicita `pwdAccountLockedTime`;
- `mapPerson()` devuelve `null` cuando la persona esta bloqueada;
- `findByUid()` filtra los resultados nulos;
- `reloadBySub()` filtra los resultados nulos.

El filtro es importante porque `Optional.findFirst()` no acepta un valor
`null`. Sin el filtro, una persona deshabilitada provocaba un
`NullPointerException` y la API respondia `500`.

Con el filtro, la persona se trata como inexistente y `AuthService` responde
con `BadCredentialsException`, que el manejador global convierte en `401`.

## Como probarlo desde CMD

Situarse en la carpeta `login-federado`:

```cmd
cd /d C:\Users\Nico\Desktop\CityPassBack\CityPass_Login_Backend\login-federado
```

Levantar el entorno:

```cmd
docker compose up --build -d
docker compose ps -a
docker compose logs ldap-config
```

Comprobar la cuenta del panel:

```cmd
docker exec citypass-ldap ldapwhoami -x -H ldap://localhost:389 -D "cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local" -w "panel-writer-secret"
```

Obtener un token de delegado y sustituir `ACCESS_TOKEN` en los comandos
siguientes:

```cmd
curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\":\"delegado-rec\",\"password\":\"changeit123\",\"clientId\":\"citypass-admin-web\"}"
```

Deshabilitar una persona:

```cmd
curl -i -X POST http://localhost:8081/panel/people/test-qa1/disable -H "Authorization: Bearer ACCESS_TOKEN"
```

Debe responder `204`.

Confirmar el atributo en LDAP:

```cmd
docker exec citypass-ldap ldapsearch -x -H ldap://localhost:389 -D "cn=admin,dc=citypass,dc=local" -w admin -b "uid=test-qa1,ou=People,ou=Reclamos,dc=citypass,dc=local" -s base pwdAccountLockedTime
```

Debe aparecer:

```text
pwdAccountLockedTime: 000001010000Z
```

Probar el login de la persona deshabilitada:

```cmd
curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\":\"test-qa1\",\"password\":\"claveTemp123\",\"clientId\":\"citypass-reclamos-web\"}"
```

Debe responder `401`, nunca `500`.

Rehabilitar:

```cmd
curl -i -X POST http://localhost:8081/panel/people/test-qa1/enable -H "Authorization: Bearer ACCESS_TOKEN"
```

Debe responder `204`. Despues, el atributo `pwdAccountLockedTime` no debe tener
ningun valor y el login valido debe responder `200`.

## Recompilar despues de cambios Java

Si se modifica `LdapDirectory.java`, reconstruir la imagen de la aplicacion:

```cmd
docker compose up -d --build app
```

Revisar errores:

```cmd
docker compose logs app --tail=200
docker compose logs openldap --tail=200
```
