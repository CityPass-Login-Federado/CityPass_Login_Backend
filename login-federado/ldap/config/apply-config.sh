#!/bin/bash
# =============================================================================
# CityPass+ — Configuración del directorio OpenLDAP (idempotente)
#
# Orden obligatorio (spec 01-DISENO-IDENTIDAD.md §2):
#   1. Cargar módulos dinámicos (memberof, refint, unique, ppolicy, constraint)
#   2. Crear los overlays sobre la base de datos de datos
#   3. Índices + hash de contraseñas + ACLs
#   4. Recién AHORA cargar el seed (ldap/config/01-seed.ldif), para que el
#      overlay memberof calcule las referencias inversas desde el primer alta.
#
# Se puede re-ejecutar: cada paso tolera "ya aplicado".
# =============================================================================
set -uo pipefail

LDAP_HOST="${LDAP_HOST:-openldap}"
ADMIN_DN="cn=admin,dc=citypass,dc=local"
ADMIN_PW="${LDAP_ADMIN_PASSWORD:?LDAP_ADMIN_PASSWORD es requerida}"
CFG_DN="cn=admin,cn=config"
CFG_PW="${LDAP_CONFIG_PASSWORD:-config}"
URL="ldap://${LDAP_HOST}:389"

echo "==> Esperando a OpenLDAP en ${URL}..."
ready=0
for i in $(seq 1 60); do
  if ldapwhoami -x -H "$URL" -D "$ADMIN_DN" -w "$ADMIN_PW" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then echo "ERROR: OpenLDAP no respondió"; exit 1; fi
echo "    OK"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# apply_cfg <archivo> <descripcion> — ldapmodify contra cn=config, tolerando
# cambios ya aplicados (OpenLDAP responde con errores distintos según el caso,
# por eso la lista de patrones es amplia y explícita).
apply_cfg() {
  local file="$1" desc="$2"
  echo "--> ${desc}"
  if ! ldapmodify -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" -f "$file" >"$TMP/out.log" 2>&1; then
    if grep -qiE "(already exists|Type or value exists|already in list)" "$TMP/out.log"; then
      echo "    ya aplicado — se omite"
    else
      echo "ERROR aplicando: ${desc}"
      cat "$TMP/out.log"
      exit 1
    fi
  fi
}

apply_data() {
  local file="$1" desc="$2"
  echo "--> ${desc}"
  if ! ldapadd -x -H "$URL" -D "$ADMIN_DN" -w "$ADMIN_PW" -f "$file" >"$TMP/data.log" 2>&1; then
    if grep -qiE "(already exists|Type or value exists)" "$TMP/data.log"; then
      echo "    ya aplicado — se omite"
    else
      echo "ERROR aplicando: ${desc}"
      cat "$TMP/data.log"
      exit 1
    fi
  fi
}

# -----------------------------------------------------------------------------
# 0) Detectar el DN de la base mdb (el índice varía entre versiones de imagen)
# -----------------------------------------------------------------------------
DB_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=config '(&(objectClass=olcDatabaseConfig)(objectClass=olcMdbConfig))' dn 2>/dev/null \
  | grep '^dn: olcDatabase=' | head -n1 | sed 's/^dn: //')
if [ -z "$DB_DN" ]; then echo "ERROR: no se encontró la base mdb"; exit 1; fi
echo "==> Base de datos: ${DB_DN}"

# La imagen osixia/openldap ya carga el esquema ppolicy del paquete slapd.
# No se debe volver a agregar ppolicy.schema completo: sus OID chocan con la
# definición existente y ldap_add termina con error 80. Algunas versiones,
# sin embargo, omiten los atributos operacionales; agregamos solo el necesario
# para la baja administrativa del panel a la entrada nativa existente.
PP_SCHEMA_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=schema,cn=config -s one \
  '(&(objectClass=olcSchemaConfig)(olcObjectClasses=*pwdPolicy*))' \
  dn 2>/dev/null | grep '^dn: ' | head -n1 | sed 's/^dn: //')
if [ -z "$PP_SCHEMA_DN" ]; then
  echo "ERROR: la imagen no tiene cargado el esquema ppolicy base"
  exit 1
fi

if ! ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=schema,cn=config -s sub '(olcAttributeTypes=*pwdAccountLockedTime*)' olcAttributeTypes 2>/dev/null \
    | grep -q 'pwdAccountLockedTime'; then
  echo "ERROR: falta pwdAccountLockedTime en el esquema de arranque"
  echo "       debe cargarse desde ldap/config/ppolicy-operational.ldif"
  exit 1
fi
echo "==> Esquema ppolicy disponible (${PP_SCHEMA_DN})"

# -----------------------------------------------------------------------------
# 1) Módulos dinámicos
# -----------------------------------------------------------------------------
cat >"$TMP/modules.ldif" <<EOF
dn: cn=module,cn=config
changetype: add
objectClass: olcModuleList
cn: module
olcModulePath: /usr/lib/ldap
olcModuleLoad: memberof.la
EOF
MODULE_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=config -s one '(objectClass=olcModuleList)' dn olcModuleLoad 2>/dev/null \
  | awk '/^dn: / { dn=$0; sub(/^dn: /, "", dn) } /olcModuleLoad: .*memberof/ { print dn; exit }')
if [ -z "$MODULE_DN" ]; then
  apply_cfg "$TMP/modules.ldif" "Cargando entrada de módulos dinámicos"
  MODULE_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
    -b cn=config -s one '(objectClass=olcModuleList)' dn olcModuleLoad 2>/dev/null \
    | awk '/^dn: / { dn=$0; sub(/^dn: /, "", dn) } /olcModuleLoad: .*memberof/ { print dn; exit }')
fi
if [ -z "$MODULE_DN" ]; then
  echo "ERROR: no se encontró la entrada de módulos dinámicos"
  exit 1
fi

for mod in refint unique ppolicy constraint; do
  cat >"$TMP/mod-$mod.ldif" <<EOF
dn: ${MODULE_DN}
changetype: modify
add: olcModuleLoad
olcModuleLoad: ${mod}.la
EOF
  apply_cfg "$TMP/mod-$mod.ldif" "Cargando módulo ${mod}"
done

# -----------------------------------------------------------------------------
# 2) Overlays
# -----------------------------------------------------------------------------

# --- memberof: referencia inversa persona→grupos (spec §2.7) ---
cat >"$TMP/ov-memberof.ldif" <<EOF
dn: olcOverlay=memberof,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcMemberOf
olcOverlay: memberof
olcMemberOfGroupOC: groupOfNames
olcMemberOfMemberAD: member
olcMemberOfMemberofAD: memberOf
olcMemberOfRefInt: TRUE
EOF
apply_cfg "$TMP/ov-memberof.ldif" "Overlay memberof"

# --- refint: integridad referencial del atributo member ---
cat >"$TMP/ov-refint.ldif" <<EOF
dn: olcOverlay=refint,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcRefintConfig
olcOverlay: refint
olcRefintAttribute: member
EOF
apply_cfg "$TMP/ov-refint.ldif" "Overlay refint"

# --- unique: unicidad GLOBAL de uid, mail y employeeNumber (D3/D5 del diseño) ---
cat >"$TMP/ov-unique.ldif" <<EOF
dn: olcOverlay=unique,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcUniqueConfig
olcOverlay: unique
olcUniqueUri: ldap:///?uid?sub
olcUniqueUri: ldap:///?mail?sub
olcUniqueUri: ldap:///?employeeNumber?sub
EOF
apply_cfg "$TMP/ov-unique.ldif" "Overlay unique (uid/mail/employeeNumber globales)"

# --- constraint: anti-anidamiento (D4). Un `member` solo puede ser una
#     persona bajo algún ou=People o el placeholder técnico ---
cat >"$TMP/ov-constraint.ldif" <<EOF
dn: olcOverlay=constraint,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcConstraintConfig
olcOverlay: constraint
olcConstraintAttribute: member regex ^(uid=[^,]+,ou=People,ou=[^,]+|cn=empty-group-placeholder,ou=ServiceAccounts),dc=citypass,dc=local$
EOF
apply_cfg "$TMP/ov-constraint.ldif" "Overlay constraint (anti-anidamiento de grupos)"

# --- ppolicy: habilita pwdAccountLockedTime (baja = bloqueo permanente, D7) ---
cat >"$TMP/ov-ppolicy.ldif" <<EOF
dn: olcOverlay=ppolicy,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcPPolicyConfig
olcOverlay: ppolicy
olcPPolicyDefault: cn=default,ou=Policies,dc=citypass,dc=local
olcPPolicyHashCleartext: TRUE
EOF
apply_cfg "$TMP/ov-ppolicy.ldif" "Overlay ppolicy"

apply_data /config/02-ppolicy-policy.ldif "Política de contraseñas por defecto (cn=default)"

# -----------------------------------------------------------------------------
# 3) Hash de contraseñas, índices y ACLs
# -----------------------------------------------------------------------------

for idx in uid mail employeeNumber member; do
  cat >"$TMP/idx-$idx.ldif" <<EOF
dn: ${DB_DN}
changetype: modify
add: olcDbIndex
olcDbIndex: ${idx} eq
EOF
  apply_cfg "$TMP/idx-$idx.ldif" "Índice ${idx}"
done

# ACLs (spec §2.8). Se REEMPLAZAN las por defecto:
#   userPassword: self escribe (cambiar la propia), anonymous solo auth (bind),
#                 panel-writer escribe pero NUNCA lee hashes (=wx).
#   ServiceAccounts: invisibles para cualquiera que no sea admin/readonly/panel.
#   Resto: admin rootdn (bypass), panel-writer escribe, readonly lee,
#          uno mismo se lee, anónimo solo autenticar.
cat >"$TMP/acls.ldif" <<EOF
dn: ${DB_DN}
changetype: modify
delete: olcAccess
-
add: olcAccess
olcAccess: {0}to attrs=userPassword
  by self write
  by anonymous auth
  by dn.exact="cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local" =wx
  by * none
-
add: olcAccess
olcAccess: {1}to attrs=memberOf
  by * read
-
add: olcAccess
olcAccess: {2}to dn.subtree="ou=ServiceAccounts,dc=citypass,dc=local"
  by dn.exact="cn=admin,dc=citypass,dc=local" write
  by dn.exact="cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local" read
  by dn.exact="cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local" read
  by * none
-
add: olcAccess
olcAccess: {3}to *
  by dn.exact="cn=admin,dc=citypass,dc=local" write
  by dn.exact="cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local" write
  by dn.exact="cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local" read
  by self read
  by anonymous auth
  by * none
EOF
apply_cfg "$TMP/acls.ldif" "ACLs del directorio"

# -----------------------------------------------------------------------------
# 4) Seed de datos — DESPUÉS de los overlays (ver comentario del encabezado)
# -----------------------------------------------------------------------------
echo "--> Cargando seed (01-seed.ldif)"
if ldapadd -x -H "$URL" -D "$ADMIN_DN" -w "$ADMIN_PW" -c -f /config/01-seed.ldif >"$TMP/seed.log" 2>&1; then
  echo "    OK"
else
  if grep -qiE "(Already exists|exists)" "$TMP/seed.log"; then
    echo "    seed ya cargado — se omite"
  else
    echo "ERROR cargando seed:"; cat "$TMP/seed.log"; exit 1
  fi
fi

echo ""
echo "== Directorio configurado correctamente =="
