# Deploy del backend de Belopia (Contabo)

El backend corre en Contabo `62.171.160.238`, un servidor **compartido** con PH v2 e Iurivia.
Belopia solo toca `/opt/belopia/dev` y `/opt/belopia/prod`.

| Ambiente | Rama | Carpeta | Contenedor | Puerto (solo 127.0.0.1) | Etiqueta del compose |
|---|---|---|---|---|---|
| DEV | `develop` | `/opt/belopia/dev` | `belopia-backend-dev` | 8111 | `dev-latest` |
| PROD | `main` | `/opt/belopia/prod` | `belopia-backend-prod` | 8110 | `latest` |

> Un merge a `main` despliega PROD solo. Antes, revisar `Checklist promocion a PROD` en el vault.

## Qué hace el workflow (`.github/workflows/deploy.yml`)

1. Construye la imagen con dos etiquetas: la del ambiente (`dev-latest` / `latest`) y
   **`sha-<7>`** (los 7 primeros caracteres del commit). Le pone etiquetas OCI
   (`org.opencontainers.image.title=belopia-backend`, `revision`) y el build-arg `GIT_SHA`.
2. Copia `deploy/ci/desplegar.sh` a la carpeta del ambiente en el servidor.
3. Corre `bash desplegar.sh <amb> adrianpuche/belopia-backend:sha-<7> <puerto>`.

## Qué hace `deploy/ci/desplegar.sh`

1. Anota el ID de la imagen que corre ahora.
2. Baja la imagen `sha-<7>`, la etiqueta como `<amb>-sha-<7>` y como la del compose, y levanta
   **solo** el contenedor del backend (`docker compose up -d --no-deps <contenedor>`).
3. Espera hasta 90 s (`HEALTH_TIMEOUT`) a que `http://127.0.0.1:<puerto>/actuator/health`
   responda `"status":"UP"`.
4. **Si no levanta:** vuelve a la imagen anterior, verifica que esa responda y sale con error.
   El job queda en rojo y GitHub avisa por mail.
5. **Si levanta:** conserva las **3 últimas** imágenes `<amb>-sha-*` y borra solo imágenes de
   Belopia sin etiqueta (filtro por la etiqueta OCI).

Reglas del servidor compartido, que el script respeta:
- Nunca `docker image prune` global ni `docker compose ... --remove-orphans`: los proyectos
  Compose `dev`/`prod` los usan también otros sistemas, y se borrarían sus contenedores.
- Nunca `docker compose down` en `/opt/belopia/*`.

## Volver a mano a una versión anterior

```bash
cd /opt/belopia/dev          # o prod
docker images adrianpuche/belopia-backend --format "{{.Tag}} {{.CreatedSince}}" | grep dev-sha-
bash desplegar.sh dev adrianpuche/belopia-backend:dev-sha-<7> 8111
```

El script acepta una imagen local, así que sirve cualquiera de las 3 conservadas.

**Después de un rollback, no correr `docker compose pull`:** `dev-latest`/`latest` en el registro
apunta a la imagen rota. La corrección va con un commit nuevo.

## Logs

Los dos compose (`docker-compose.dev.yml`, `docker-compose.prod.yml`) rotan los logs del
contenedor: `json-file`, 5 archivos de 20 MB (100 MB máximo por ambiente).

```bash
docker logs belopia-backend-dev -f --tail 100
```

Los compose de este repo son la referencia. El del servidor (`/opt/belopia/<amb>/docker-compose.yml`)
se actualiza a mano: guardar antes un backup `.bak-<motivo>-<fecha>` y no cambiar otros servicios.
