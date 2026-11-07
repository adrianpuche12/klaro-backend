// Mejora continua v4 (k6): login igual que la app (password grant directo a Keycloak con el cliente publico
// klaro-frontend), contra el Keycloak PROPIO del ambiente descartable. Nunca se loguea la clave ni el token.
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://backend:8080';
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://keycloak:8080';
const REALM = __ENV.KEYCLOAK_REALM || 'belopia-load';
const CLAVE = __ENV.LOAD_USER_PASSWORD;

/** Access token del usuario sintetico. Falla la corrida si no puede entrar (sin token no hay prueba). */
export function login(username) {
    if (!CLAVE) fail('Falta LOAD_USER_PASSWORD (la genera levantar.sh)');
    const r = http.post(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
        { grant_type: 'password', client_id: 'klaro-frontend', username, password: CLAVE },
        { tags: { name: 'POST keycloak token' } });
    const ok = check(r, { 'login 200': (x) => x.status === 200 });
    if (!ok) fail(`login fallo para ${username}: HTTP ${r.status}`);
    return r.json('access_token');
}

/** Params con el token y un nombre de endpoint fijo (agrupa /shifts/{id}/sales en una sola metrica). */
export function conNombre(token, nombre, extraHeaders = {}) {
    return {
        headers: Object.assign({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, extraHeaders),
        tags: { name: nombre },
    };
}
