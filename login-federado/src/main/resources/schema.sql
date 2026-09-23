-- =============================================================================
-- CityPass+ Login Federado — esquema.
--
-- CONTRATO (importante): este archivo es SEGURO de re-ejecutar porque NUNCA
-- borra nada: todo es CREATE TABLE/INDEX IF NOT EXISTS. Con
-- sql.init.mode=always corre en CADA arranque en todos los ambientes (dev,
-- CI/smoke, prod) y en una base ya creada es un no-op.
--
-- Lo que este archivo NO hace a propósito: migrar un formato viejo a uno
-- nuevo (ej: si cambia una columna). Para eso, hibernate.hbm2ddl ddl-auto
-- está en `validate`: si el código y la base divergen, la app NO arranca
-- en vez de corromper datos. El cambio destructivo se hace con migración
-- manual versionada (ver docs/runbooks/schema-prod.md), nunca editando
-- este archivo para borrar y recrear.
-- =============================================================================

-- Refresh tokens OPACOS con rotación y cadena (spec §4.2 / D9).
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    sub VARCHAR(16) NOT NULL,                -- employeeNumber (U000042)
    chain_id UUID NOT NULL,                  -- sesión: todos los eslabones
    client_id VARCHAR(255) NOT NULL,         -- para reemitir misma audience
    audience VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256; nunca el valor crudo
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL                -- NULL = vivo
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_sub ON refresh_tokens (sub);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_chain ON refresh_tokens (chain_id);

-- Intentos de login para la ventana deslizante (ADR-004).
CREATE TABLE IF NOT EXISTS login_attempts (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    successful BOOLEAN NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_attempts_username ON login_attempts (username);
CREATE INDEX IF NOT EXISTS idx_login_attempts_attempted_at ON login_attempts (attempted_at);

-- Auditoría del panel: cada mutación queda registrada (manual del panel §8:
-- "las altas, bajas y cambios de grupos quedan registrados").
CREATE TABLE IF NOT EXISTS panel_audit (
    id UUID PRIMARY KEY,
    actor_sub VARCHAR(16) NOT NULL,          -- quién (delegado)
    actor_uid VARCHAR(255) NOT NULL,
    module VARCHAR(64) NOT NULL,             -- módulo afectado/scope
    action VARCHAR(64) NOT NULL,             -- ej: PERSON_CREATED
    target VARCHAR(512) NOT NULL,            -- DN o identificador objetivo
    detail VARCHAR(1024),
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_panel_audit_module ON panel_audit (module);
CREATE INDEX IF NOT EXISTS idx_panel_audit_occurred_at ON panel_audit (occurred_at);

-- Recupero por token de un solo uso: se persiste SOLO el hash SHA-256, jamás
-- el valor crudo. Una sola fila activa por cuenta (al pedir otro se borra el
-- anterior en la misma transacción). LDAP se escribe recién al canjear.
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY,
    sub VARCHAR(16) NOT NULL,                  -- employeeNumber dueño del token
    uid VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,    -- SHA-256; nunca el valor crudo
    requested_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL                     -- NULL = activo
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_sub ON password_reset_tokens (sub);

-- Registro de solicitudes ACEPTADAS para el limitador (cooldown por cuenta,
-- topes por cuenta e IP). Las rechazadas no se guardan: responder 204 igual.
CREATE TABLE IF NOT EXISTS password_reset_requests (
    id UUID PRIMARY KEY,
    uid VARCHAR(255) NOT NULL,                 -- normalizado (trim + minúsculas)
    ip_address VARCHAR(45),
    requested_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_password_reset_requests_uid ON password_reset_requests (uid);
CREATE INDEX IF NOT EXISTS idx_password_reset_requests_ip ON password_reset_requests (ip_address);
CREATE INDEX IF NOT EXISTS idx_password_reset_requests_at ON password_reset_requests (requested_at);
