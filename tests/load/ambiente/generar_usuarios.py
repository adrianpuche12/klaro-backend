#!/usr/bin/env python3
"""
Mejora continua v4 (k6): genera los usuarios SINTETICOS del ambiente descartable de Belopia.

Escribe en el directorio de salida (por defecto tests/load/.generado/, ignorado por git):
  - realm-belopia-load.json  realm "belopia-load" para el Keycloak PROPIO del compose (escrito a mano
                             aca, nunca exportado de un servidor): cliente publico klaro-frontend con
                             password grant (el mismo azp que valida el backend), roles de realm
                             root/staff, el atributo tenant_id en el token y los usuarios @belopia.test
                             con el primer ingreso resuelto (clave definitiva, sin acciones pendientes).
  - usuarios.sql             filas de app_users (keycloak_id = sub del token) y user_store_access.
  - usuarios.json            lista para k6: usuario, papel y empresa. SIN la clave.

La clave de todos los usuarios sale de LOAD_USER_PASSWORD (la genera levantar.sh en cada corrida con
openssl). Nunca se escribe en el repo: solo en .generado/, que muere con el runner.
Solo biblioteca estandar.
"""
import hashlib
import json
import os
import sys
import uuid

REALM = "belopia-load"
CLIENTE = "klaro-frontend"

# Empresas de prueba (ids fijos: los usa semilla.sql)
EMPRESAS = [(1, "a"), (2, "b"), (3, "c")]

# Usuarios por papel y por empresa (se pueden cambiar por variable; a confirmar con la linea de base)
POR_EMPRESA = {
    "root": 1,
    "encargado": 1,
    "contador": 1,
    "cajero": int(os.environ.get("LOAD_CAJEROS", "10")),
}
# Papel -> (rol de realm de Keycloak, nombre del Role de la empresa en semilla.sql o None para root)
PAPELES = {
    "root": ("root", None),
    "encargado": ("staff", "Encargado"),
    "contador": ("staff", "Contador"),
    "cajero": ("staff", "Cajero"),
}

# User Profile del realm: tenant_id declarado (lo edita solo admin, como en los realms reales),
# para que Keycloak 24+ no descarte el atributo.
PERFIL_DE_USUARIO = {
    "unmanagedAttributePolicy": "ADMIN_EDIT",
    "attributes": [
        {"name": "username", "displayName": "${username}",
         "validations": {"length": {"min": 3, "max": 255}, "username-prohibited-characters": {},
                         "up-username-not-idn-homograph": {}},
         "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}, "multivalued": False},
        {"name": "email", "displayName": "${email}",
         "validations": {"email": {}, "length": {"max": 255}},
         "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}, "multivalued": False},
        {"name": "firstName", "displayName": "${firstName}",
         "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
         "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}, "multivalued": False},
        {"name": "lastName", "displayName": "${lastName}",
         "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
         "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}, "multivalued": False},
        {"name": "tenant_id", "displayName": "tenant_id", "validations": {},
         "permissions": {"view": ["admin"], "edit": ["admin"]}, "multivalued": False},
    ],
    "groups": [{"name": "user-metadata", "displayHeader": "User metadata",
                "displayDescription": "Attributes, which refer to user metadata"}],
}


def uid(texto):
    """UUID determinista (misma semilla = mismo sub en Keycloak y en app_users)."""
    return str(uuid.UUID(hashlib.md5(("belopia-load:" + texto).encode()).hexdigest()))


def usuarios():
    for tenant, letra in EMPRESAS:
        for papel, cantidad in POR_EMPRESA.items():
            for n in range(1, cantidad + 1):
                username = f"{papel}{n}.{letra}"
                yield {
                    "id": uid(username),
                    "username": username,
                    "email": f"{username}@belopia.test",
                    "nombre": f"{papel.title()} {n}",
                    "apellido": f"Carga {letra.upper()}",
                    "papel": papel,
                    "tenant": tenant,
                    "empresa": f"carga-{letra}",
                }


def sql_txt(v):
    return "'" + str(v).replace("'", "''") + "'"


def main():
    salida = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", ".generado")
    clave = os.environ.get("LOAD_USER_PASSWORD")
    if not clave or len(clave) < 12:
        sys.exit("Falta LOAD_USER_PASSWORD (12+ caracteres, la genera levantar.sh)")
    os.makedirs(salida, exist_ok=True)
    lista = list(usuarios())

    realm = {
        "realm": REALM,
        "enabled": True,
        "sslRequired": "none",
        "registrationAllowed": False,
        "loginWithEmailAllowed": True,
        "duplicateEmailsAllowed": False,
        # tokens largos: los escenarios no tienen que renovar a mitad de la corrida (el real es ~5 min)
        "accessTokenLifespan": 3600,
        "ssoSessionIdleTimeout": 7200,
        "ssoSessionMaxLifespan": 36000,
        "bruteForceProtected": False,
        "roles": {"realm": [{"name": "root"}, {"name": "staff"}, {"name": "platform"}]},
        "clients": [{
            "clientId": CLIENTE,
            "name": "Belopia (ambiente de carga)",
            "enabled": True,
            "publicClient": True,
            "directAccessGrantsEnabled": True,
            "standardFlowEnabled": False,
            "implicitFlowEnabled": False,
            "serviceAccountsEnabled": False,
            "protocol": "openid-connect",
            "fullScopeAllowed": True,
            "protocolMappers": [{
                "name": "tenant_id",
                "protocol": "openid-connect",
                "protocolMapper": "oidc-usermodel-attribute-mapper",
                "consentRequired": False,
                "config": {
                    "user.attribute": "tenant_id",
                    "claim.name": "tenant_id",
                    "jsonType.label": "String",
                    "access.token.claim": "true",
                    "id.token.claim": "true",
                    "userinfo.token.claim": "true",
                    "introspection.token.claim": "true",
                },
            }],
        }],
        "components": {
            "org.keycloak.userprofile.UserProfileProvider": [{
                "providerId": "declarative-user-profile",
                "subComponents": {},
                "config": {"kc.user.profile.config": [json.dumps(PERFIL_DE_USUARIO)]},
            }],
        },
        "users": [{
            "id": u["id"],
            "username": u["username"],
            "email": u["email"],
            "emailVerified": True,
            "firstName": u["nombre"],
            "lastName": u["apellido"],
            "enabled": True,
            "requiredActions": [],
            "attributes": {"tenant_id": [str(u["tenant"])]},
            "credentials": [{"type": "password", "value": clave, "temporary": False}],
            "realmRoles": [PAPELES[u["papel"]][0]],
        } for u in lista],
    }
    with open(os.path.join(salida, f"realm-{REALM}.json"), "w", encoding="utf-8") as f:
        json.dump(realm, f, ensure_ascii=False, indent=1)

    lineas = [
        "-- Generado por tests/load/ambiente/generar_usuarios.py: SOLO datos sinteticos del ambiente descartable.",
        "\\set ON_ERROR_STOP on",
        "BEGIN;",
    ]
    for u in lista:
        rol = PAPELES[u["papel"]][1]
        role_id = "NULL" if rol is None else f"(SELECT id FROM roles WHERE tenant_id = {u['tenant']} AND name = {sql_txt(rol)})"
        # store_id: el primer local de la empresa para el personal; root sin local (todos)
        store_id = "NULL" if rol is None else f"(SELECT min(id) FROM store WHERE tenant_id = {u['tenant']})"
        lineas.append(
            "INSERT INTO app_users (tenant_id, keycloak_id, username, full_name, store_id, status, role_id, created_at) VALUES "
            f"({u['tenant']}, {sql_txt(u['id'])}, {sql_txt(u['username'])}, {sql_txt(u['nombre'] + ' ' + u['apellido'])}, "
            f"{store_id}, 'ACTIVE', {role_id}, now());")
        if rol is not None:
            # el personal tiene acceso a todos los locales de su empresa
            lineas.append(
                "INSERT INTO user_store_access (user_id, store_id, tenant_id) "
                f"SELECT u.id, s.id, s.tenant_id FROM app_users u JOIN store s ON s.tenant_id = u.tenant_id "
                f"WHERE u.keycloak_id = {sql_txt(u['id'])};")
    lineas.append("COMMIT;")
    with open(os.path.join(salida, "usuarios.sql"), "w", encoding="utf-8") as f:
        f.write("\n".join(lineas) + "\n")

    publico = [{"username": u["username"], "papel": u["papel"], "empresa": u["empresa"], "tenant": u["tenant"]}
               for u in lista]
    with open(os.path.join(salida, "usuarios.json"), "w", encoding="utf-8") as f:
        json.dump(publico, f, ensure_ascii=False, indent=1)
    print(f"{len(lista)} usuarios sinteticos en {len(EMPRESAS)} empresas -> {os.path.abspath(salida)}")


if __name__ == "__main__":
    main()
