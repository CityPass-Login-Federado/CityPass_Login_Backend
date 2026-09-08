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
    if grep -qiE "(already exists|Type or value exists|modifications require|no such attribute|Object class violation|No such object|Undefined attribute|undefined)" "$TMP/out.log"; then
    if grep -qiE "(already exists|Type or value exists|modifications require|no such attribute)" "$TMP/out.log"; then
      echo "    ya aplicado — se omite"
    else
      echo "ERROR aplicando: ${desc}"; cat "$TMP/out.log"; exit 1
    fi
  fi
}

# apply_overlay <RDN> <archivo> <descripcion> — crea un overlay sobre ${DB_DN}
# SOLO si ya no existe. La idempotencia por regex de errores no alcanza: un
# overlay que ya está registrado puede devolver "Type or value exists" o no,
# según la versión; verificar antes es determinista.
apply_overlay() {
  local rdn="$1" file="$2" desc="$3"
  local overlay="${rdn#*=}"
  # El RDN se numera ({0}, {1}...) al crearse: nunca asumir el RDN exacto.
  # Buscar por el VALOR del atributo olcOverlay bajo la base mdb.
  if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
      -b "$DB_DN" "(&(objectClass=olcOverlayConfig)(olcOverlay=${overlay}))" \
      dn 2>/dev/null | grep -q "^dn: olcOverlay={.*${overlay},"; then
    echo "--> ${desc} — ya aplicado, se omite"
    return
  fi
  apply_cfg "$file" "$desc"
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
# 1) Módulos dinámicos
# -----------------------------------------------------------------------------
cat >"$TMP/modules.ldif" <<EOF
dn: cn=module,cn=config
changetype: add
objectClass: olcModuleList
cn: module
# La imagen base crea una única entrada cn=module{0}; usar un DN "cn=module"
# sin índice aquí es el bug clásico que rompe la idempotencia (OpenLDAP crea
# una entrada nueva por cada intento). Detectamos la entrada real para operar
# SIEMPRE sobre esa.
MODULE_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b cn=config '(objectClass=olcModuleList)' dn 2>/dev/null \
  | grep -E '^dn: cn=module\{' | head -n1 | sed 's/^dn: //')
if [ -z "$MODULE_DN" ]; then echo "ERROR: no se encontró la entrada cn=module en cn=config"; exit 1; fi
echo "==> Entrada de módulos: ${MODULE_DN}"

# Asegurar que la primera tenga olcModulePath (la imagen base no siempre la trae)
if ! ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" -b "$MODULE_DN" olcModulePath 2>/dev/null | grep -q '^olcModulePath:'; then
  cat >"$TMP/module-path.ldif" <<EOF
dn: ${MODULE_DN}
changetype: modify
add: olcModulePath
olcModulePath: /usr/lib/ldap
olcModuleLoad: memberof.la
EOF
apply_cfg "$TMP/modules.ldif" "Cargando entrada de módulos dinámicos"
  apply_cfg "$TMP/module-path.ldif" "Fijando olcModulePath"
fi

for mod in refint unique ppolicy constraint; do
# Cargar cada módulo faltante, de a uno, sobre la entrada REAL detectada.
# La imagen base precarga back_mdb/memberof/refint con valores tipo "{1}memberof"
# (sin extensión .la); aceptamos ambos formatos al chequear.
for mod in memberof refint unique ppolicy constraint; do
  if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" -b "$MODULE_DN" \
      olcModuleLoad 2>/dev/null | grep -qE "^olcModuleLoad: .*${mod}(\.la)?\s*$"; then
    echo "--> Módulo ${mod} ya cargado — se omite"
    continue
  fi
  cat >"$TMP/mod-$mod.ldif" <<EOF
dn: cn=module,cn=config
dn: ${MODULE_DN}
changetype: modify
add: olcModuleLoad
olcModuleLoad: ${mod}.la
EOF
  apply_cfg "$TMP/mod-$mod.ldif" "Cargando módulo ${mod}"
done

# Limpieza de entradas cn=module{1..N} espurias que pudo dejar un script viejo:
# cada una solo añade ruido y puede duplicar overlays. Conservamos {0}.
for extra in $(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
    -b cn=config '(objectClass=olcModuleList)' dn 2>/dev/null \
    | grep -E '^dn: cn=module\{[1-9][0-9]*\},cn=config' | sed 's/^dn: //'); do
  $(command -v ldapdelete) -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" "$extra" >/dev/null 2>&1 \
    && echo "--> Eliminada entrada de módulo espuria: ${extra}"
done

# -----------------------------------------------------------------------------
# 1.5) Esquema ppolicy
# -----------------------------------------------------------------------------
# La imagen base trae el esquema ppolicy completo en cn=schema,cn=config
# (incluidos los operacionales pwdAccountLockedTime, pwdFailureTime, etc.).
# Verificación defensiva: si faltara, se carga la versión del sistema.
if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
    -b cn=schema,cn=config olcAttributeTypes 2>/dev/null \
    | grep -q "pwdAccountLockedTime"; then
  echo "--> Esquema ppolicy operativo (incluye pwdAccountLockedTime) — se omite"
else
  echo "--> Esquema ppolicy incompleto; cargando base"
  ldapadd -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" -c -f /etc/ldap/schema/ppolicy.ldif >/dev/null 2>&1 || true
fi

# -----------------------------------------------------------------------------
# 2) Overlays
# -----------------------------------------------------------------------------

# --- memberof: referencia inversa persona→grupos (spec §2.7) ---
# La imagen base precarga un overlay memberof con la config DEFAULT que usa
# groupOfUniqueNames/uniqueMember: incompatible con nuestro modelo (grupos
# groupOfNames + atributo member). Ajustamos la config existente al modelo.
# El RDN se numera: detectamos el DN real bajo la base.
MO_DN=$(ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
  -b "$DB_DN" "(&(objectClass=olcMemberOf)(olcOverlay=memberof))" dn 2>/dev/null \
  | grep '^dn: olcOverlay={.*memberof' | sed 's/^dn: //')
if [ -z "$MO_DN" ]; then
  echo "ERROR: overlay memberof no encontrado bajo ${DB_DN}"; exit 1
fi
cat >"$TMP/ov-memberof.ldif" <<EOF
dn: olcOverlay=memberof,${DB_DN}
changetype: add
objectClass: olcOverlayConfig
objectClass: olcMemberOf
olcOverlay: memberof
dn: ${MO_DN}
changetype: modify
replace: olcMemberOfGroupOC
olcMemberOfGroupOC: groupOfNames
-
replace: olcMemberOfMemberAD
olcMemberOfMemberAD: member
olcMemberOfMemberofAD: memberOf
olcMemberOfRefInt: TRUE
-
replace: olcMemberOfMemberOfAD
olcMemberOfMemberOfAD: memberOf
EOF
apply_cfg "$TMP/ov-memberof.ldif" "Overlay memberof"
apply_cfg "$TMP/ov-memberof.ldif" "Overlay memberof (config al modelo groupOfNames)"

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
apply_overlay "olcOverlay=refint" "$TMP/ov-refint.ldif" "Overlay refint"

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
apply_overlay "olcOverlay=unique" "$TMP/ov-unique.ldif" "Overlay unique (uid/mail/employeeNumber globales)"

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
apply_overlay "olcOverlay=constraint" "$TMP/ov-constraint.ldif" "Overlay constraint (anti-anidamiento de grupos)"

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
apply_overlay "olcOverlay=ppolicy" "$TMP/ov-ppolicy.ldif" "Overlay ppolicy"

# -----------------------------------------------------------------------------
# 3) Hash de contraseñas, índices y ACLs
# -----------------------------------------------------------------------------

# Las contraseñas que lleguen sin esquema (ej. reset desde el panel) se guardan
# hasheadas con SSHA a nivel servidor: el backend nunca manipula hashes.
cat >"$TMP/hash.ldif" <<EOF
dn: ${DB_DN}
# olcPasswordHash pertenece a olcFrontendConfig, no a la base mdb.
if ldapsearch -x -H "$URL" -D "$CFG_DN" -w "$CFG_PW" \
    -b "olcDatabase={-1}frontend,cn=config" olcPasswordHash 2>/dev/null \
    | grep -q '^olcPasswordHash:'; then
  echo "--> olcPasswordHash {SSHA} ya configurado — se omite"
else
  cat >"$TMP/hash.ldif" <<EOF
dn: olcDatabase={-1}frontend,cn=config
changetype: modify
add: olcPasswordHash
olcPasswordHash: {SSHA}
EOF
apply_cfg "$TMP/hash.ldif" "olcPasswordHash {SSHA}"
  apply_cfg "$TMP/hash.ldif" "olcPasswordHash {SSHA}"
fi

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