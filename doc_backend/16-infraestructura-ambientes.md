# Infraestructura y Ambientes — PH-Backend-v2

Documento de referencia operativa completa para los tres ambientes del sistema Humberto V2.
Incluye credenciales, URLs, configuraciones y registro de cambios realizados.

**Última actualización:** 2026-05-17

---

## 1. Servidor

| Campo         | Valor                                                          |
|---------------|----------------------------------------------------------------|
| IP pública    | `62.171.160.238`                                               |
| Usuario SSH   | `root`                                                         |
| Clave SSH     | `C:\Users\jorge\.ssh\github_actions_key`                       |
| Comando SSH   | `ssh -i C:/Users/jorge/.ssh/github_actions_key root@62.171.160.238` |

---

## 2. Ambientes y puertos

| Componente        | Localhost (dev local)        | DEV (servidor)                 | PROD (servidor)                |
|-------------------|------------------------------|--------------------------------|--------------------------------|
| Frontend URL      | `http://localhost:8081`      | `http://62.171.160.238:8103`   | `http://62.171.160.238:8102`   |
| Backend URL       | `http://localhost:8080`      | `http://62.171.160.238:8101`   | `http://62.171.160.238:8100`   |
| Keycloak URL      | `http://62.171.160.238:8095` | `http://62.171.160.238:8095`   | `http://62.171.160.238:8095`   |
| Keycloak realm    | `proyecto-h-dev`             | `proyecto-h-dev`               | `proyecto-h-prod`              |
| Spring profile    | `local`                      | `dev`                          | `prod`                         |
| R2 carpeta        | `local/comprobantes/`        | `dev/comprobantes/`            | `prod/comprobantes/`           |
| NeonDB branch     | `ep-red-water-ac3dna23`      | `ep-red-water-ac3dna23`        | `ep-gentle-frog-acm7ltaw`      |

---

## 3. Base de datos — NeonDB (PostgreSQL)

Proveedor: [neon.tech](https://neon.tech) — misma cuenta, distintos branches.
Las credenciales completas están en los archivos `.env` del servidor (ver sección 6).

### DEV

```
Host (branch): ep-red-water-ac3dna23
URL:           jdbc:postgresql://ep-red-water-ac3dna23-pooler.sa-east-1.aws.neon.tech/neondb?sslmode=require
Usuario:       neondb_owner
Password:      [ver .env en servidor — NO commitear]
ddl-auto:      update
```

### PROD

```
Host (branch): ep-gentle-frog-acm7ltaw
URL:           jdbc:postgresql://ep-gentle-frog-acm7ltaw-pooler.sa-east-1.aws.neon.tech/neondb?sslmode=require
Usuario:       neondb_owner
Password:      [ver .env en servidor — NO commitear]
ddl-auto:      update
```

> Son bases de datos completamente aisladas — un cambio en DEV no afecta PROD.

---

## 4. Keycloak — Autenticación

Panel de administración: `http://62.171.160.238:8095`

| Campo             | Valor                  |
|-------------------|------------------------|
| Usuario admin     | `admin`                           |
| Password admin    | [ver .env en servidor — NO commitear] |
| Cliente app       | `proyecto-h-client`    |
| Tipo de cliente   | Público (sin secret)   |
| Grant type        | `password` (Resource Owner) |

### Realm DEV — `proyecto-h-dev`

**Usuarios:**

| Usuario       | Rol   | Nombre        |
|---------------|-------|---------------|
| `admin-dev`   | admin | Admin DEV     |
| `adrian123`   | admin | Adrian Pucheta|
| `gimena_alba` | user  | Gimena Alba   |
| `maria1234`   | user  | Maria Rosa    |

> Las contraseñas de usuarios DEV se gestionan desde el panel Keycloak. No se documentan aquí por seguridad operativa.

**Cliente `proyecto-h-client` — Web Origins:**
```
http://62.171.160.238:8103
http://localhost:8081
```

**Cliente `proyecto-h-client` — Redirect URIs:**
```
http://62.171.160.238:8103/*
http://localhost:8081/*
```

**Configuración:**
- `sslRequired`: `none`
- `directAccessGrantsEnabled`: `true`
- `publicClient`: `true`

### Realm PROD — `proyecto-h-prod`

**Usuarios:**

| Usuario    | Password                          | Rol   | Descripción             |
|------------|-----------------------------------|-------|-------------------------|
| `ph-admin` | [ver .env en servidor — NO commitear] | admin | Administrador principal |

**Roles disponibles:** `admin`, `user`

**Cliente `proyecto-h-client` — Web Origins:**
```
http://62.171.160.238:8102
http://62.171.160.238:8103
http://localhost:8081
```

**Cliente `proyecto-h-client` — Redirect URIs:**
```
http://62.171.160.238:8102/*
http://62.171.160.238:8103/*
http://localhost:8081/*
```

**Configuración:**
- `sslRequired`: `none`  ← cambiado de `external` a `none` (servidor sin TLS)
- `directAccessGrantsEnabled`: `true`
- `publicClient`: `true`

> Si en el futuro se agrega HTTPS al servidor, cambiar `sslRequired` a `external` en ambos realms.

---

## 5. Almacenamiento — Cloudflare R2

Usado para guardar fotos de comprobantes de operaciones.

| Campo          | Valor                                                                  |
|----------------|------------------------------------------------------------------------|
| Account ID     | [ver .env en servidor — NO commitear]  |
| Access Key ID  | [ver .env en servidor — NO commitear]  |
| Secret Key     | [ver .env en servidor — NO commitear]  |
| Bucket         | `humberto-comprobantes`                |
| Endpoint       | `https://{ACCOUNT_ID}.r2.cloudflarestorage.com` |
| URL pública    | `https://pub-7e31005d201d4d34894758b2b1d00d9a.r2.dev` |

**Separación por ambiente (carpetas dentro del mismo bucket):**

| Ambiente  | Carpeta en R2                  | Variable                     |
|-----------|--------------------------------|------------------------------|
| Localhost | `local/comprobantes/`          | `R2_FOLDER_PREFIX=local`     |
| DEV       | `dev/comprobantes/`            | `R2_FOLDER_PREFIX=dev`       |
| PROD      | `prod/comprobantes/`           | `R2_FOLDER_PREFIX=prod`      |

El bucket es único pero las imágenes de cada ambiente van a carpetas separadas — no hay mezcla de archivos entre DEV y PROD.

Endpoint del upload: `POST /api/v2/uploads/comprobante`

---

## 6. Variables de entorno completas

### DEV — `/opt/humberto-v2/dev/.env`

```env
DB_URL=jdbc:postgresql://ep-red-water-ac3dna23-pooler.sa-east-1.aws.neon.tech/neondb?sslmode=require
DB_USER=neondb_owner
DB_PASSWORD=<PASSWORD_NEONDB>
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8080
KEYCLOAK_URL=http://172.21.0.2:8080
KEYCLOAK_REALM=proyecto-h-dev
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=<PASSWORD_KEYCLOAK_ADMIN>
KEYCLOAK_ISSUER_URL=http://62.171.160.238:8095
R2_ACCOUNT_ID=<R2_ACCOUNT_ID>
R2_ACCESS_KEY_ID=<R2_ACCESS_KEY_ID>
R2_SECRET_ACCESS_KEY=<R2_SECRET_KEY>
R2_BUCKET_NAME=humberto-comprobantes
R2_ENDPOINT=https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com
R2_PUBLIC_URL=https://pub-7e31005d201d4d34894758b2b1d00d9a.r2.dev
R2_FOLDER_PREFIX=dev
```

### PROD — `/opt/humberto-v2/prod/.env`

```env
DB_URL=jdbc:postgresql://ep-gentle-frog-acm7ltaw-pooler.sa-east-1.aws.neon.tech/neondb?sslmode=require
DB_USER=neondb_owner
DB_PASSWORD=<PASSWORD_NEONDB>
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
KEYCLOAK_URL=http://172.21.0.2:8080
KEYCLOAK_REALM=proyecto-h-prod
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=<PASSWORD_KEYCLOAK_ADMIN>
KEYCLOAK_ISSUER_URL=http://62.171.160.238:8095
R2_ACCOUNT_ID=<R2_ACCOUNT_ID>
R2_ACCESS_KEY_ID=<R2_ACCESS_KEY_ID>
R2_SECRET_ACCESS_KEY=<R2_SECRET_KEY>
R2_BUCKET_NAME=humberto-comprobantes
R2_ENDPOINT=https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com
R2_PUBLIC_URL=https://pub-7e31005d201d4d34894758b2b1d00d9a.r2.dev
R2_FOLDER_PREFIX=prod
```

> Los valores reales de todas las variables marcadas con `<...>` están únicamente en los archivos `.env` del servidor. Nunca deben commitearse al repositorio.

---

## 7. Docker — Contenedores en servidor

| Contenedor                   | Imagen                                          | Puerto externo | Puerto interno | Ambiente |
|------------------------------|-------------------------------------------------|---------------|----------------|----------|
| `humberto-v2-backend-prod`   | `adrianpuche/humberto-v2-backend:latest`        | `8100`        | `8080`         | PROD     |
| `humberto-v2-frontend-prod`  | `adrianpuche/humberto-v2-frontend:latest`       | `8102`        | `80`           | PROD     |
| `humberto-v2-backend-dev`    | `adrianpuche/humberto-v2-backend:dev-latest`    | `8101`        | `8080`         | DEV      |
| `humberto-v2-frontend-dev`   | `adrianpuche/humberto-v2-frontend:dev-latest`   | `8103`        | `80`           | DEV      |

**Ubicaciones en servidor:**

```
/opt/humberto-v2/
├── dev/
│   ├── docker-compose.yml
│   └── .env
└── prod/
    ├── docker-compose.yml
    └── .env
```

### docker-compose.yml — DEV

```yaml
services:
  humberto-v2-backend-dev:
    image: adrianpuche/humberto-v2-backend:dev-latest
    container_name: humberto-v2-backend-dev
    restart: unless-stopped
    ports:
      - "8101:8080"
    env_file: .env
    networks:
      - default
      - geronimo_default

  humberto-v2-frontend-dev:
    image: adrianpuche/humberto-v2-frontend:dev-latest
    container_name: humberto-v2-frontend-dev
    restart: unless-stopped
    ports:
      - "8103:80"
    env_file: .env

networks:
  default:
    name: humberto-v2_default
  geronimo_default:
    external: true
```

### docker-compose.yml — PROD

```yaml
services:
  humberto-v2-backend-prod:
    image: adrianpuche/humberto-v2-backend:latest
    container_name: humberto-v2-backend-prod
    restart: unless-stopped
    ports:
      - "8100:8080"
    env_file: .env
    networks:
      - default
      - geronimo_default

  humberto-v2-frontend-prod:
    image: adrianpuche/humberto-v2-frontend:latest
    container_name: humberto-v2-frontend-prod
    restart: unless-stopped
    ports:
      - "8102:80"
    env_file: .env

networks:
  default:
    name: humberto-v2-prod_default
  geronimo_default:
    external: true
```

---

## 8. CI/CD — GitHub Actions

> **Belopia:** este documento describe PH v2. El deploy de Belopia (imagen `sha-<7>`, chequeo de salud con rollback automático, rotación de logs) está en [`deploy/README.md`](../deploy/README.md).

**Repositorios GitHub:**
- Backend: `https://github.com/adrianpuche12/PH-Backend-v2`
- Frontend: `https://github.com/adrianpuche12/PH-Frontend-v2`

**Flujo automático:**

| Push a rama       | Tag Docker    | Ambiente destino |
|-------------------|---------------|------------------|
| `develop`         | `dev-latest`  | DEV              |
| `master` o `main` | `latest`      | PROD             |

**Secrets configurados en GitHub (ambos repos):**

| Secret              | Descripción                               |
|---------------------|-------------------------------------------|
| `DOCKER_USERNAME`   | Usuario Docker Hub                        |
| `DOCKER_PASSWORD`   | Password Docker Hub                       |
| `SERVER_HOST`       | IP del servidor                           |
| `SERVER_USER`       | Usuario SSH del servidor                  |
| `SSH_PRIVATE_KEY`   | Contenido de la clave privada SSH         |
| `DB_URL_PROD`       | URL completa NeonDB PROD (ver sección 3)  |
| `DB_URL_DEV`        | URL completa NeonDB DEV (ver sección 3)   |

**Pasos del workflow (backend y frontend):**
1. Checkout del código
2. Detectar ambiente según rama (`main`/`master` → prod, `develop` → dev)
3. Login a Docker Hub
4. Build y push de imagen con tag correspondiente
5. SSH al servidor → `docker compose pull && docker compose up -d && docker image prune -f`

---

## 9. Config dinámica del frontend (`config.ts`)

El frontend detecta el ambiente automáticamente por `window.location.hostname` y `port`:

```
hostname = 62.171.160.238
  port 8103  →  DEV   (realm: proyecto-h-dev,  apiUrl: :8101)
  port 8102  →  PROD  (realm: proyecto-h-prod, apiUrl: :8100)

hostname = localhost
             →  LOCAL (realm: proyecto-h-dev,  apiUrl: localhost:8080)
```

---

## 10. Comandos útiles en servidor

```bash
# Ver estado de todos los contenedores Humberto
docker ps --filter "name=humberto" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# Logs en tiempo real
docker logs humberto-v2-backend-prod -f --tail 50
docker logs humberto-v2-backend-dev  -f --tail 50

# Reiniciar contenedores
docker restart humberto-v2-backend-prod
docker restart humberto-v2-backend-dev

# Deploy manual PROD
cd /opt/humberto-v2/prod && docker compose pull && docker compose up -d && docker image prune -f

# Deploy manual DEV
cd /opt/humberto-v2/dev && docker compose pull && docker compose up -d && docker image prune -f
```

---

## 11. Flujo de trabajo

```
Desarrollo local
  └── Backend:  cd PH-Backend-v2/PH && ./mvnw spring-boot:run
  └── Frontend: cd PH-Frontend-v2   && npx expo start --web
  └── Conecta a: NeonDB DEV + Keycloak realm proyecto-h-dev

Push a develop
  └── GitHub Actions: build → tag dev-latest → deploy DEV automático
  └── QA en: http://62.171.160.238:8103

Merge develop → master
  └── GitHub Actions: build → tag latest → deploy PROD automático
  └── Verificar: http://62.171.160.238:8102

REGLA: Nunca trabajar directamente en master. Siempre develop → master.
```

---

## 12. Registro de cambios realizados — 2026-05-17

Cambios aplicados durante la sesión de puesta en producción:

### Backend — `PH-Backend-v2`

| Archivo | Cambio |
|---------|--------|
| `application-prod.properties` | `ddl-auto` cambiado de `validate` a `update` para permitir inicialización automática del schema en NeonDB PROD vacío |
| `storage/service/R2StorageService.java` | Agregado campo `folderPrefix` (`@Value("${r2.folder-prefix:prod}")`). La ruta de archivo ahora es `{folderPrefix}/{folder}/{timestamp}_{uuid}.ext` para aislar imágenes por ambiente |
| `application.properties` | Agregada propiedad `r2.folder-prefix=${R2_FOLDER_PREFIX:local}` |
| `.github/workflows/deploy.yml` | Agregada rama `master` al trigger (`branches: [main, master, develop]`) y condición `OR master` en la detección de ambiente |

### Servidor — configuración aplicada manualmente

| Acción | Detalle |
|--------|---------|
| Creado `/opt/humberto-v2/prod/` | Directorio del ambiente PROD |
| Creado `/opt/humberto-v2/prod/docker-compose.yml` | Compose con contenedores PROD en puertos 8100/8102 |
| Creado `/opt/humberto-v2/prod/.env` | Variables de entorno PROD completas |
| Corregido `/opt/humberto-v2/dev/.env` | `SPRING_PROFILES_ACTIVE` corregido de `prod` → `dev` |
| Agregadas variables R2 a DEV `.env` | DEV no tenía variables R2, uploads fallaban |
| Agregado `R2_FOLDER_PREFIX=dev` a DEV `.env` | Aísla imágenes DEV en carpeta `dev/` |
| Agregado `R2_FOLDER_PREFIX=prod` a PROD `.env` | Aísla imágenes PROD en carpeta `prod/` |
| Eliminado contenedor `humberto-v2-backend` (build local) | Ocupaba el puerto 8100, bloqueando el backend PROD |

### Keycloak — cambios aplicados via API

| Realm | Acción |
|-------|--------|
| `proyecto-h-prod` | Creados roles `admin` y `user` |
| `proyecto-h-prod` | Creado usuario `ph-admin` con password `PH@Admin2025!` y rol `admin` |
| `proyecto-h-prod` | `sslRequired` cambiado de `external` a `none` |
| `proyecto-h-prod` | Web Origins del cliente actualizadas: agregado `http://62.171.160.238:8102` (PROD) — el error era que solo tenía el puerto 8103 (DEV), lo que bloqueaba el login por CORS |

### Garantía de aislamiento — estado final

| Capa | DEV | PROD | ¿Aislados? |
|------|-----|------|-----------|
| NeonDB | `ep-red-water-ac3dna23` | `ep-gentle-frog-acm7ltaw` | ✅ Sí — branches distintos |
| Keycloak | realm `proyecto-h-dev` | realm `proyecto-h-prod` | ✅ Sí — realms distintos |
| Docker image | tag `dev-latest` | tag `latest` | ✅ Sí — tags distintos |
| R2 imágenes | carpeta `dev/` | carpeta `prod/` | ✅ Sí — carpetas distintas |
| Spring profile | `dev` | `prod` | ✅ Sí — configs distintas |
| Puertos | 8101/8103 | 8100/8102 | ✅ Sí — sin conflicto |
| CI/CD trigger | rama `develop` | rama `master` | ✅ Sí — ramas distintas |
