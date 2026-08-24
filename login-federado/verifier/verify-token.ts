/**
 * Verificador del contrato de tokens — CERO dependencias (solo node:crypto).
 *
 * Espejo exacto de specs/03-CONTRATO-TOKEN.md: cada regla del contrato es un
 * chequeo explícito acá. Los siete módulos pueden copiar esta carpeta tal
 * cual y validar sus tokens sin instalar npm ni conocer el código Java.
 */
import crypto from "node:crypto";

export const ISSUER = "https://idp.citypass.local";
export const CONTRACT_VERSION = 1;
export const MODULES = [
  "movilidad", "residuos", "reclamos", "emergencias", "espacios", "analitica",
] as const;

/** D6: nombres de grupo en minúsculas/números/guiones. */
const GROUP_NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/;
/** D3: el sub humano SIEMPRE es U + 6 dígitos. */
const EMPLOYEE_NUMBER = /^U\d{6}$/;

export interface VerifyResult {
  ok: boolean;
  errors: string[];
}

function b64urlToJson(part: string): Record<string, unknown> {
  const json = Buffer.from(part, "base64url").toString("utf8");
  return JSON.parse(json);
}

/**
 * Valida firma RSA-SHA256 y TODAS las reglas del contrato. Nunca lanza:
 * devuelve la lista de violaciones para que el módulo decida qué loguear.
 */
export function verifyAccessToken(token: string, publicKeyPem: string): VerifyResult {
  const errors: string[] = [];

  const parts = token.split(".");
  if (parts.length !== 3) {
    return { ok: false, errors: ["formato: un JWT tiene exactamente 3 segmentos"] };
  }
  const [headB64, payB64, sigB64] = parts;

  // ---- Firma (antes que nada: sin validez criptográfica no se lee nada) --
  let header: Record<string, unknown>;
  let claims: Record<string, unknown>;
  try {
    header = b64urlToJson(headB64);
    claims = b64urlToJson(payB64);
  } catch {
    return { ok: false, errors: ["formato: header o payload no son JSON base64url"] };
  }

  if (header.alg !== "RS256") {
    errors.push(`header.alg: esperado RS256, llegó ${JSON.stringify(header.alg)}`);
  } else {
    const signature = Buffer.from(sigB64, "base64url");
    const verified = crypto
      .createVerify("RSA-SHA256")
      .update(`${headB64}.${payB64}`)
      .verify(publicKeyPem, signature);
    if (!verified) errors.push("firma: RSA-SHA256 inválida");
  }

  // ---- Header ------------------------------------------------------------
  if (typeof header.kid !== "string" || header.kid.length === 0) {
    errors.push("header.kid: ausente o vacío (debe ser la huella RFC 7638)");
  }

  // ---- Claims comunes ----------------------------------------------------
  if (claims.iss !== ISSUER) {
    errors.push(`iss: esperado "${ISSUER}", llegó ${JSON.stringify(claims.iss)}`);
  }

  // La regla más rota del mundo JWT: aud DEBE ser lista siempre.
  if (!Array.isArray(claims.aud) || claims.aud.length === 0 ||
      !claims.aud.every((a) => typeof a === "string")) {
    errors.push("aud: debe ser UN ARRAY de strings con al menos un elemento");
  }

  if (claims.ver !== CONTRACT_VERSION) {
    errors.push(`ver: esperado ${CONTRACT_VERSION}, llegó ${JSON.stringify(claims.ver)}`);
  }

  if (typeof claims.jti !== "string" || claims.jti.length === 0) {
    errors.push("jti: ausente o vacío");
  }

  const now = Math.floor(Date.now() / 1000);
  if (typeof claims.exp !== "number" || typeof claims.iat !== "number") {
    errors.push("exp/iat: deben ser timestamps numéricos");
  } else {
    if (claims.exp <= now) errors.push("exp: token EXPIRADO");
    if (claims.exp <= claims.iat) errors.push("exp: debe ser posterior a iat");
  }

  const tokenUse = claims.token_use;
  if (tokenUse !== "human" && tokenUse !== "service") {
    errors.push(`token_use: esperado "human" o "service", llegó ${JSON.stringify(tokenUse)}`);
    return { ok: false, errors };
  }

  // ---- Rama humana -------------------------------------------------------
  if (tokenUse === "human") {
    if (typeof claims.sub !== "string" || !EMPLOYEE_NUMBER.test(claims.sub)) {
      errors.push(`sub: humano debe ser U+6 dígitos (employeeNumber), llegó ${JSON.stringify(claims.sub)} — NUNCA el uid`);
    }
    if (typeof claims.preferred_username !== "string" || claims.preferred_username.length === 0) {
      errors.push("preferred_username: ausente o vacío");
    }
    if (typeof claims.module !== "string" ||
        !(MODULES as readonly string[]).includes(claims.module)) {
      errors.push(`module: debe ser uno de ${MODULES.join("|")} en minúsculas, llegó ${JSON.stringify(claims.module)}`);
    }

    const groups = claims.groups;
    if (!Array.isArray(groups)) {
      errors.push("groups: debe existir como array (vacío es válido)");
    } else {
      for (const g of groups) {
        if (typeof g !== "string" || !GROUP_NAME.test(g)) {
          errors.push(`groups: "${String(g)}" viola D6/D2 (pelado, minúsculas-números-guiones; jamás DN ni ROLE_)`);
        }
      }
    }
    if ("roles" in claims) {
      errors.push("roles: PROHIBIDO (D1) — los grupos van en 'groups', sin mapeo a roles");
    }
  }

  // ---- Rama de servicio --------------------------------------------------
  if (tokenUse === "service") {
    if (typeof claims.sub !== "string" || claims.sub.length === 0) {
      errors.push("sub: el client_id del servicio");
    }
    if (typeof claims.namespace !== "string" || claims.namespace.length === 0) {
      errors.push("namespace: obligatorio para tokens de servicio (viaja al bus)");
    }
    for (const forbidden of ["groups", "preferred_username", "module"]) {
      if (forbidden in claims) {
        errors.push(`${forbidden}: PROHIBIDO en tokens de servicio`);
      }
    }
  }

  return { ok: errors.length === 0, errors };
}
