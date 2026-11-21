# Ensayo de la promoción (V1 → última migración)

`main` tiene una sola migración (`V1__baseline_schema.sql`). El día de la promoción, Flyway aplica todas las demás juntas sobre la base de PROD, y el backend arranca por primera vez con RLS, la guardia de RLS y los permisos que fallan cerrados. Este ensayo hace ese mismo salto **en un ambiente descartable**, antes de mergear a `main`.

Corre en el runner de GitHub Actions (`.github/workflows/promocion.yml`) o en un Docker local. Usa `docker-compose.promocion.yml`, con Postgres, Keycloak y S3 propios en una red sin salida a internet. **Nunca se conecta a DEV, PROD ni al Keycloak compartido.** Todos los datos son sintéticos.

## Cuándo corre
- En todo PR que cambie migraciones, este ensayo o la semilla de usuarios. Eso incluye el PR de `develop` a `main` (la promoción).
- A mano (`workflow_dispatch`), cuando el workflow esté en la rama por defecto.
- En local: `tests/promocion/ensayar.sh` (las 3 variantes) o `tests/promocion/ensayar.sh prod-vacia`. Para bajar todo: `ensayar.sh bajar`. En Windows: `PYTHON=python MSYS_NO_PATHCONV=1 bash tests/promocion/ensayar.sh`.

## Variantes
| Variante | Base de partida | Tiene que |
|---|---|---|
| `prod-vacia` | V1 sin filas (la forma de PROD hoy) | migrar a la última versión y arrancar con perfil `prod` y guardia de RLS en OK |
| `con-datos` | V1 con datos sintéticos con forma de septiembre (`v1-datos.sql`) | migrar sin perder ninguna fila |
| `casos-borde` | igual, más un turno abierto repetido y un stock negativo | **fallar en V14**, sin arrancar, y decir qué limpiar |

En las dos que migran, después se cargan usuarios sintéticos y se prueba:
- un login por papel (root, encargado, cajero y contador);
- que el encargado de una empresa no vea un local de otra;
- que un usuario de Keycloak **sin fila en `app_users`** quede sin acceso (falla cerrada), y qué pasa con un root sin fila.

## Qué sube
Solo `tests/promocion/resultados/<variante>/informe.md`, sin datos ni tokens (el workflow lo verifica antes de subir). Los logs del backend y de Flyway quedan en el runner.

## La verificación previa
`verificacion-previa.sql` son consultas de **solo lectura** (transacción `READ ONLY` y `ROLLBACK`) que devuelven versiones y conteos, nunca datos. El ensayo las corre sobre la base sintética. Son las mismas que se corren en PROD antes de promover, **a mano y solo con OK explícito de Jorge** (checklist de promoción):

Se copia el archivo al contenedor (`docker cp`) y se corre con `docker exec belopia-postgres psql -X -U <dueño> -d belopia_prod -f /tmp/verificacion-previa.sql`, igual que el resto de los scripts remotos: con `ssh … 'bash -s' < script`, nunca con `docker exec -i`.

Si algo no da 0, se limpia en PROD con una orden aparte. Las migraciones no se reescriben.
