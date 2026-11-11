-- V16 — Código de turno único por empresa (propuesta "Código de turno que choca entre
-- empresas y en el mismo minuto", Corrida k6 2 del 2026-11-08; P1 aprobada por Jorge el 11-Nov).
--
-- Antes: shifts.code era UNIQUE en toda la base (shifts_code_key, de la línea de base) y el
-- código era T-<fecha>-<HHmm>-<3 letras del local>. Dos empresas con locales que empiezan
-- igual, abriendo en el mismo minuto, chocaban: la segunda no podía abrir la caja (400).
-- Ahora la unicidad es por empresa (tenant_id, code). El código además lleva el id del local y
-- un correlativo del día (ShiftService.generateCode), así que tampoco choca dentro de la empresa.
--
-- Segura con datos existentes: si hoy el código es único en toda la base, también lo es por
-- empresa (verificado en DEV el 11-Nov, solo lectura: 0 duplicados). Igual se frena con un
-- mensaje claro si apareciera alguno. No se borra ni se renombra ningún turno.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM shifts GROUP BY tenant_id, code HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V16: hay códigos de turno repetidos dentro de una misma empresa; revisar antes de migrar';
    END IF;
END $$;

-- Primero el índice nuevo y después se suelta el viejo: nunca queda la columna sin unicidad.
CREATE UNIQUE INDEX IF NOT EXISTS ux_shifts_tenant_code ON shifts (tenant_id, code);
ALTER TABLE shifts DROP CONSTRAINT IF EXISTS shifts_code_key;
