-- V5 — SPRINT-14: enlaza app_users a la nueva tabla roles.
-- business_role/user_permissions quedan deprecados (no se borran todavía,
-- se migran a un Role equivalente) para no perder el trabajo hecho a mano
-- en SPRINT-09 (ej. el usuario "carlos" en DEV con businessRole=contador).
ALTER TABLE app_users ADD COLUMN role_id BIGINT REFERENCES roles(id);

-- Migración de datos: un Role por cada usuario que ya tenía business_role
-- seteado (patrón simple 1:1 por usuario -- el volumen de datos real hoy
-- es mínimo; si dos usuarios del mismo tenant terminan con el mismo nombre
-- de business_role, se desambigua agregando el id de usuario al nombre).
DO $$
DECLARE
    u RECORD;
    new_role_id BIGINT;
    role_name TEXT;
BEGIN
    FOR u IN
        SELECT id, tenant_id, business_role
        FROM app_users
        WHERE business_role IS NOT NULL AND role_id IS NULL
    LOOP
        role_name := u.business_role;
        IF EXISTS (SELECT 1 FROM roles WHERE tenant_id = u.tenant_id AND name = role_name) THEN
            role_name := u.business_role || ' (' || u.id || ')';
        END IF;

        INSERT INTO roles (tenant_id, name, level, can_manage_users)
        VALUES (u.tenant_id, role_name, 1, false)
        RETURNING id INTO new_role_id;

        INSERT INTO role_permissions (role_id, permission)
        SELECT new_role_id, permission FROM user_permissions WHERE user_id = u.id;

        UPDATE app_users SET role_id = new_role_id WHERE id = u.id;
    END LOOP;
END $$;
