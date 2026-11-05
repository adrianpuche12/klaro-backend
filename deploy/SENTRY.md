# Sentry en el backend de Belopia

- Se activa con `SENTRY_DSN` en el `.env` del ambiente (vacío = apagado). `sentry.environment`
  es `development` en DEV y `production` en PROD.
- **Versión:** la imagen trae `SENTRY_RELEASE=belopia-backend@<commit>` (build-arg `GIT_SHA` en
  `.github/workflows/deploy.yml`, `ARG`/`ENV` en `PH/Dockerfile`). Así cada error queda asociado
  al deploy que lo introdujo y, después de un rollback, se ve qué versión corre.
- **Etiquetas por evento** (`balance.config.SentryContextProcessor`, test `SentryContextProcessorTest`):
  - `tenant_id`: la empresa del token (`TenantContext`).
  - `store_id`: el local de la URL (`/stores/{storeId}/...` o `?storeId=`), solo si es numérico.
  - Ningún dato personal: ni usuario, ni email, ni cuerpo del request.
- Para ver los errores de una empresa: `tenant_id:<id>` en el buscador de Issues.
- El frontend usa el mismo esquema: `release = belopia-frontend@<commit>` (`app.config.js` → `extra.release`).
- Las reglas de alerta se configuran en el panel de Sentry, no en el código.
