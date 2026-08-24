/**
 * Corre la batería: node verifier/run.ts
 * Sale con código 1 si algún caso "válido" falla o algún caso roto pasa —
 * útil como gate en CI de cualquiera de los siete módulos.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { verifyAccessToken } from "./verify-token.ts";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const publicKey = fs.readFileSync(path.resolve(HERE, "..", "keys", "public_key.pem"), "utf8");
const cases: { name: string; token: string; expectValid: boolean }[] =
  JSON.parse(fs.readFileSync(path.join(HERE, "tokens.json"), "utf8"));

let failures = 0;

console.log("Verificador del contrato CityPass+ (specs/03-CONTRATO-TOKEN.md)\n");
for (const c of cases) {
  const result = verifyAccessToken(c.token, publicKey);
  const asExpected = result.ok === c.expectValid;
  if (!asExpected) failures++;
  const icon = asExpected ? "OK  " : "FALLA";
  console.log(`${icon} ${c.name}`);
  for (const err of result.errors) {
    console.log(`       - ${err}`);
  }
}

console.log(`\n${cases.length} casos, ${failures} resultados inesperados`);
process.exit(failures === 0 ? 0 : 1);
