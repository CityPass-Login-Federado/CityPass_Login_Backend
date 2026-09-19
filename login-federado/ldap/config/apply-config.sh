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
# cambios ya aplicados SOLO cuando el error SEMÁNTICO indica eso (la entrada
# o el valor ya existen). Cualquier "Object class violation", "no such object"
# o "undefined attribute" es un fallo REAL del LDIF y debe abortar.
apply_cfg() {
  local file="$1" desc="$2"
  echo "--> ${desc}"
  if ! ldapmodify -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" -f "$file" >"$TMP/out.log" 2>&1; then
    if grep -qiE "(already exists|Type or value exists|modifications require|no such attribute)" "$TMP/out.log"; then
      echo "    ya aplicado — se omite"
    else
      echo "ERROR aplicando: ${desc}"; cat "$TMP/out.log"; exit 1
    fi
  fi
}

# apply_dit <archivo> <descripcion> — ldapadd contra el DIT (dc=citypass,dc=local),
# usando la cuenta admin del DIT (no la de cn=config). Con -c salta las
# entradas ya existentes y solo aborta si hay un error real.
apply_dit() {
  local file="$1" desc="$2"
  echo "--> ${desc}"
  if ! ldapadd -x -H "$URL" -D "$ADMIN_DN" -w "$ADMIN_PW" -c -f "$file" >"$TMP/out.log" 2>&1; then
    local real
    real=$(grep -iE "^ldap_add: " "$TMP/out.log" | grep -icvE "Already exists|Type or value exists")
    if [ "$real" -gt 0 ]; then
      echo "ERROR aplicando: ${desc}"; cat "$TMP/out.log"; exit 1
    fi
    echo "    ya aplicado — se omite"
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

# -----------------------------------------------------------------------------
# 1) Esquema ppolicy (objeto pwdPolicy / pwdAccountLockedTime)
#
# En OpenLDAP 2.4 los módulos NO auto-registran su esquema: si el overlay
# ppolicy se activa sin la definición de pwdPolicy, el atributo de bloqueo
# (cualquier update del panel sobre el) y la entrada de política fallan con
# "undefined object class". Cómo se resuelve sin romper la imagen:
#   * /config/ppolicy.schema (canónico de OpenLDAP 2.4) se convierte a LDIF
#     con el propio slaptest de la imagen (no se escribe la conversión a mano)
#     y se carga por cn=config.
#   * Si no hay slaptest o falla la conversión, se salta con advertencia: la
#     app sigue bloqueando en su capa de código (LdapDirectory.mapPerson)
#     pero se pierde la garantía "el directorio rechaza el bind".
# -----------------------------------------------------------------------------
PP_READY=0
if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
    -b cn=schema,cn=config '(cn=*ppolicy*)' dn 2>/dev/null | grep -q '^dn:'; then
  PP_READY=1
else
  if [ -f /config/ppolicy.schema ] && command -v slaptest >/dev/null 2>&1; then
    echo "==> Esquema ppolicy (conversión vía slaptest)"
    mkdir -p "$TMP/pp-cfg"
    printf 'include /etc/ldap/schema/core.schema\ninclude /config/ppolicy.schema\n' > "$TMP/pp-slapd.conf"
    if slaptest -f "$TMP/pp-slapd.conf" -F "$TMP/pp-out" >"$TMP/pp-slaptest.log" 2>&1; then
      PP_LDIF=$(find "$TMP/pp-out" -path '*cn=schema*' -name 'cn=*ppolicy.ldif' | head -n1)
      if [ -n "$PP_LDIF" ]; then
        sed -i -E '1s/^dn: cn=\{[0-9]+\}ppolicy,/dn: cn=ppolicy,/' "$PP_LDIF"
        sed -i -E 's/^((olcAttributeTypes|olcObjectClasses|olcMatchingRules|olcSyntaxes): )\{[0-9]+\}/\1/' "$PP_LDIF"
        apply_cfg "$PP_LDIF" "Esquema ppolicy (pwdPolicy/pwdAccountLockedTime)"
        PP_READY=1
      else
        echo "    ADVERTENCIA: slaptest no generó la entrada ppolicy — se omite"
      fi
    else
      echo "    ADVERTENCIA: no se pudo convertir el esquema ppolicy — se omite (la app igual cubre D7 en código)"
    fi
  else
    echo "    ADVERTENCIA: sin slaptest o sin /config/ppolicy.schema — ppolicy no disponible"
  fi
fi

# -----------------------------------------------------------------------------
# 2) Módulos dinámicos
#
# osixia/openldap:1.5.0 ya crea cn=module{0} con back_mdb, memberof y refint.
# Si existe, se le agregan los módulos que faltan; si no existe, se crea uno
# nuevo. La clave es operar SIEMPRE sobre el DN real (cn=module{N}), porque
# cn=module sin índice es ambiguo cuando hay más de una entrada cn=module*.
# -----------------------------------------------------------------------------
MOD_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=config -s one '(objectClass=olcModuleList)' dn 2>/dev/null \
  | grep '^dn: cn=module{' | head -n1 | sed 's/^dn: //')
if [ -z "$MOD_DN" ]; then
  cat >"$TMP/modules.ldif" <<EOF
dn: cn=module{0},cn=config
changetype: add
objectClass: olcModuleList
cn: module{0}
olcModulePath: /usr/lib/ldap
olcModuleLoad: memberof.la
EOF
  apply_cfg "$TMP/modules.ldif" "Creando entrada de módulos"
  MOD_DN="cn=module{0},cn=config"
fi
echo "==> Módulos en: ${MOD_DN}"

for mod in refint unique ppolicy constraint; do
  if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
      -b "$MOD_DN" -s base "(olcModuleLoad=*${mod}*)" dn 2>/dev/null | grep -q '^dn:'; then
    echo "--> Módulo ${mod} ya cargado — se omite"
  else
    cat >"$TMP/mod-${mod}.ldif" <<EOF
dn: ${MOD_DN}
changetype: modify
add: olcModuleLoad
olcModuleLoad: ${mod}.la
EOF
    apply_cfg "$TMP/mod-${mod}.ldif" "Cargando módulo ${mod}"
  fi
done

# -----------------------------------------------------------------------------
# 3) Overlays
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

# La entrada que referencian olcPPolicyDefault/olcPPolicyUseLockout solo se
# carga si el esquema quedó disponible (evita romper el bootstrap con
# "undefined object class" si la imagen no pudo cargarlo).
if [ "$PP_READY" -eq 1 ]; then
  apply_dit /config/02-ppolicy-policy.ldif "Política de contraseñas por defecto (cn=default)"
else
  echo "    ADVERTENCIA: sin esquema ppolicy no se aplica la política — solo aplica la capa de código"
fi

# -----------------------------------------------------------------------------
# 4) Hash de contraseñas, índices y ACLs
# -----------------------------------------------------------------------------

# Las contraseñas que lleguen sin esquema (ej. reset desde el panel) se guardan
# hasheadas con SSHA a nivel servidor: el backend nunca manipula hashes.
# olcPasswordHash es un MAY de olcGlobal/olcFrontendConfig: va en cn=config.
cat >"$TMP/hash.ldif" <<EOF
dn: cn=config
changetype: modify
add: olcPasswordHash
olcPasswordHash: {SSHA}
EOF
apply_cfg "$TMP/hash.ldif" "olcPasswordHash {SSHA}"

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

# Si el esquema ppolicy está disponible, se agrega una ACL explícita para
# pwdAccountLockedTime (atributo operacional que el panel escribe al
# deshabilitar/habilitar personas). Sin el esquema cargado, el handler
# de olcAccess rechaza la referencia al atributo.
if [ "$PP_READY" -eq 1 ]; then
  cat >"$TMP/acl-ppolicy.ldif" <<EOF
dn: ${DB_DN}
changetype: modify
add: olcAccess
olcAccess: {4}to attrs=pwdAccountLockedTime
  by dn.exact="cn=panel-writer,ou=ServiceAccounts,dc=citypass,dc=local" write
  by self read
  by * none
EOF
  apply_cfg "$TMP/acl-ppolicy.ldif" "ACL pwdAccountLockedTime (panel-writer escribe)"
fi

# -----------------------------------------------------------------------------
# 5) Seed de datos — DESPUÉS de los overlays (ver comentario del encabezado)
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
