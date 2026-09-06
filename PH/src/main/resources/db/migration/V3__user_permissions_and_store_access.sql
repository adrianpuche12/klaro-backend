-- V3 — SPRINT-09: tablas de permisos por módulo y locales accesibles.
-- Sin tenant_id propio: el aislamiento se hereda de app_users.tenant_id /
-- store.tenant_id vía FK. Toda query directa sobre estas tablas debe
-- hacer JOIN contra app_users/store para filtrar por tenant.
CREATE TABLE user_permissions (
    user_id    BIGINT      NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE user_store_access (
    user_id  BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    store_id BIGINT NOT NULL REFERENCES store(id)     ON DELETE CASCADE,
    PRIMARY KEY (user_id, store_id)
);
