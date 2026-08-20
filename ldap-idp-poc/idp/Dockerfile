FROM node:22-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY tsconfig.json ./
COPY src/ ./src/
COPY config/ ./config/
COPY scripts/ ./scripts/

# The signing key lives here and is mounted from a named volume. It is
# generated on first boot only (see src/keys.ts ensureKey) -- regenerating it
# per boot would invalidate every token ever issued.
ENV KEYS_DIR=/keys
ENV CONFIG_FILE=/app/config/idp.json

EXPOSE 8080

# ponytail: run the TypeScript directly with tsx. A compile step buys nothing
# for a PoC and is one more thing that can be stale in the image.
CMD ["npx", "tsx", "src/server.ts"]
