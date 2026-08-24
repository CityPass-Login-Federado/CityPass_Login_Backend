# Verificador del contrato de tokens

Carpeta **autónoma y cero dependencias** (no hay `npm install`): solo Node ≥ 22.
Los otros seis módulos pueden copiarla tal cual para validar tokens emitidos
por el IdP contra `specs/03-CONTRATO-TOKEN.md`.

## Uso

```bash
# 1) Generar la batería de casos (buenos y rotos).
#    Reutiliza las claves que genera el backend en ../keys/;
#    si no existen, las crea para poder probar sin levantar la app.
node make-battery.ts

# 2) Verificar la batería completa (sale 1 si algo falla → sirve para CI).
node run.ts

# 3) Verificar UN token cualquiera desde su módulo:
import { verifyAccessToken } from "./verify-token.ts";
const r = verifyAccessToken(token, fs.readFileSync("keys/public_key.pem", "utf8"));
// r.ok === false ? r.errors : "token conforme al contrato"
```

## Qué chequea (`verify-token.ts`)

Firma RSA-SHA256 · `alg`/`kid` · `iss` · **`aud` siempre array** · `ver=1` ·
`jti` · `exp/iat` · `token_use` human/service · humano: `sub`=U+6dígitos,
`preferred_username`, `module`, `groups` pelados (D2/D6), sin `roles` (D1) ·
servicio: `namespace`, sin groups/module/preferred_username.
