/**
 * Generador de la batería de tokens de prueba (casos buenos y rotos).
 * Cero dependencias: usa node:crypto. Reutiliza las MISMAS claves que
 * genera el backend (keys/private_key.pem + keys/public_key.pem); si no
 * existen, las crea para poder probar sin levantar la app.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KEYS_DIR = path.resolve(HERE, "..", "keys");
const PRIVATE_KEY = path.join(KEYS_DIR, "private_key.pem");
const PUBLIC_KEY = path.join(KEYS_DIR, "public_key.pem");

function ensureKeys(): void {
  if (fs.existsSync(PRIVATE_KEY) && fs.existsSync(PUBLIC_KEY)) return;
  fs.mkdirSync(KEYS_DIR, { recursive: true });
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  fs.writeFileSync(PRIVATE_KEY, privateKey.export({ type: "pkcs8", format: "pem" }));
  fs.writeFileSync(PUBLIC_KEY, publicKey.export({ type: "spki", format: "pem" }));
  console.log("Claves generadas en", KEYS_DIR);
}

/** kid = huella RFC 7638: SHA-256 del JWK canónico {e,kty,n}. */
function thumbprintKid(publicKeyPem: string): string {
  const jwk = crypto.createPublicKey(publicKeyPem).export({ format: "jwk" }) as crypto.JsonWebKey;
  const canonical = JSON.stringify({ e: jwk.e, kty: jwk.kty, n: jwk.n });
  return crypto.createHash("sha256").update(canonical).digest("base64url");
}

const b64url = (obj: unknown): string =>
  Buffer.from(JSON.stringify(obj)).toString("base64url");

function sign(payload: Record<string, unknown>, privatePem: string, kid: string): string {
  const header = b64url({ alg: "RS256", typ: "JWT", kid });
  const body = b64url(payload);
  const signature = crypto
    .createSign("RSA-SHA256")
    .update(`${header}.${body}`)
    .sign(privatePem);
  return `${header}.${body}.${signature.toString("base64url")}`;
}

ensureKeys();
const priv = fs.readFileSync(PRIVATE_KEY, "utf8");
const pub = fs.readFileSync(PUBLIC_KEY, "utf8");
const kid = thumbprintKid(pub);

const now = Math.floor(Date.now() / 1000);

interface Case { name: string; token: string; expectValid: boolean; }

const humanBase = {
  iss: "https://idp.citypass.local",
  iat: now,
  exp: now + 15 * 60,
  jti: crypto.randomUUID(),
  token_use: "human",
  ver: 1,
};

const cases: Case[] = [
  {
    name: "humano-válido-reclamos",
    expectValid: true,
    token: sign({
      ...humanBase,
      aud: ["citypass-reclamos-api"],
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: ["soporte-n2", "delegados"],
    }, priv, kid),
  },
  {
    name: "transversal-admin-sin-grupos",
    expectValid: true,
    token: sign({
      ...humanBase,
      aud: ["citypass-admin-api"],
      sub: "U000006",
      preferred_username: "delegado-mov",
      module: "movilidad",
      groups: [],
    }, priv, kid),
  },
  {
    name: "servicio-grupo5-con-namespace",
    expectValid: true,
    token: sign({
      iss: "https://idp.citypass.local",
      iat: now,
      exp: now + 60 * 60,
      jti: crypto.randomUUID(),
      token_use: "service",
      ver: 1,
      aud: ["citypass-bus"],
      sub: "svc-grupo5",
      namespace: "grupo5",
    }, priv, kid),
  },
  {
    name: "ROTO-aud-como-string-no-lista",
    expectValid: false,
    token: sign({
      ...humanBase,
      aud: "citypass-reclamos-api",
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: [],
    } as never, priv, kid),
  },
  {
    name: "ROTO-roles-y-grupos-ROLE_",
    expectValid: false,
    token: sign({
      ...humanBase,
      aud: ["citypass-reclamos-api"],
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: ["ROLE_soporte"],
      roles: ["SOPORTE"],
    }, priv, kid),
  },
  {
    name: "ROTO-sub-es-username-no-U000042",
    expectValid: false,
    token: sign({
      ...humanBase,
      aud: ["citypass-reclamos-api"],
      sub: "jperez",
      preferred_username: "jperez",
      module: "reclamos",
      groups: [],
    }, priv, kid),
  },
  {
    name: "ROTO-ver-inexistente",
    expectValid: false,
    token: sign({
      ...humanBase,
      ver: undefined,
      aud: ["citypass-reclamos-api"],
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: [],
    }, priv, kid),
  },
  {
    name: "ROTO-expirado",
    expectValid: false,
    token: sign({
      ...humanBase,
      iat: now - 3600,
      exp: now - 60,
      aud: ["citypass-reclamos-api"],
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: [],
    }, priv, kid),
  },
  {
    name: "ROTO-firma-corrupta",
    expectValid: false,
    token: (() => {
      const t = sign({ ...humanBase, aud: ["x"], sub: "U000001", preferred_username: "a", module: "movilidad", groups: [] }, priv, kid);
      const [h, p] = t.split(".");
      // Un byte cambiado en el payload rompe la firma RSA
      const tampered = Buffer.from(p, "base64url");
      tampered[0] ^= 0x01;
      return `${h}.${tampered.toString("base64url")}.${t.split(".")[2]}`;
    })(),
  },
  {
    name: "ROTO-groups-como-DN-completo",
    expectValid: false,
    token: sign({
      ...humanBase,
      aud: ["citypass-reclamos-api"],
      sub: "U000042",
      preferred_username: "jperez",
      module: "reclamos",
      groups: ["cn=delegados,ou=Groups,ou=Reclamos,dc=citypass,dc=local"],
    }, priv, kid),
  },
  {
    name: "ROTO-servicio-con-groups-prohibido",
    expectValid: false,
    token: sign({
      iss: "https://idp.citypass.local",
      iat: now,
      exp: now + 600,
      jti: crypto.randomUUID(),
      token_use: "service",
      ver: 1,
      aud: ["citypass-bus"],
      sub: "svc-grupo1",
      namespace: "grupo1",
      groups: ["filtrado"],
    }, priv, kid),
  },
];

fs.writeFileSync(path.join(HERE, "tokens.json"), JSON.stringify(cases, null, 2));
console.log(`Batería generada: ${cases.length} casos → verifier/tokens.json (kid=${kid})`);
