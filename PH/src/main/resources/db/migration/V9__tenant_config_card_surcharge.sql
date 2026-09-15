-- V9 — SPRINT-12 (2/4): recargo por pago con tarjeta, configurable por tenant.
-- NULL = sin recargo (respeta tenants/países donde no aplica).
ALTER TABLE tenant_config ADD COLUMN card_surcharge_rate NUMERIC(5,4);
