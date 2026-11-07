// Mejora continua v4 (k6): el POS reintenta una venta con la MISMA Idempotency-Key (SalesController, V15).
// 10 VUs; en cada iteracion una clave nueva que se manda varias veces A LA VEZ (http.batch) y despues en serie,
// sobre el local 92 (producto 92001 con stock alto y un turno abierto).
// Check de negocio: UNA sola venta por clave. El resto: replay (200 con Idempotent-Replayed: true, mismo id)
// o 409 "se esta procesando". Ninguna 5xx. Al final, las ventas del turno son exactamente las claves que vendieron.
// Si falla es un HALLAZGO (venta duplicada = P1): no se arregla en este PR.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, idempotencia, venta } from './lib/datos.js';
import { resumen, porEndpoint, SIN_429 } from './lib/resumen.js';

const duplicadas = new Counter('ventas_duplicadas');
const clavesConVenta = new Counter('claves_con_venta');
const respuestas5xx = new Counter('respuestas_5xx');

const I = idempotencia();
const VUS = Number(__ENV.VUS || 10);
const PARALELOS = Number(__ENV.PARALELOS || 4);
const REINTENTOS = Number(__ENV.REINTENTOS || 2);
const ITERACIONES = Number(__ENV.ITERACIONES || 100);
const VENTA = 'POST /api/v2/shifts/{id}/sales (misma Idempotency-Key)';
const ESPERADOS = http.expectedStatuses(200, 409); // 409 = la primera todavia se esta procesando

export const options = {
    scenarios: {
        reintentos: { executor: 'shared-iterations', vus: VUS, iterations: ITERACIONES, maxDuration: '5m' },
    },
    thresholds: Object.assign({
        ventas_duplicadas: ['count==0'],
        respuestas_5xx: ['count==0'],
        'checks{tipo:idempotencia}': ['rate==1'],
    }, SIN_429, porEndpoint(['POST keycloak token', 'GET /api/v2/shifts/{id}/sales', VENTA])),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const cajeros = usuarios('cajero', 'carga-a').map((u) => login(u.username));
    const root = login(usuario('root', 0, 'carga-a').username);
    // ventas que ya tenia el turno (otra corrida sobre el mismo ambiente)
    const antes = http.get(`${BASE_URL}/api/v2/shifts/${I.turno}/sales`, conNombre(root, 'GET /api/v2/shifts/{id}/sales'));
    return { cajeros, root, ventasAntes: antes.status === 200 ? antes.json().length : 0 };
}

export default function (s) {
    const n = exec.scenario.iterationInTest;
    const token = s.cajeros[n % s.cajeros.length];
    const clave = `idem-${exec.scenario.startTime}-${n}`;
    const cuerpo = venta(I.producto, 1);
    const params = () => {
        const p = conNombre(token, VENTA, { 'Idempotency-Key': clave });
        p.responseCallback = ESPERADOS;
        return p;
    };

    const juntas = http.batch(Array.from({ length: PARALELOS }, () => ({
        method: 'POST', url: `${BASE_URL}/api/v2/shifts/${I.turno}/sales`, body: cuerpo, params: params(),
    })));
    const serie = [];
    for (let i = 0; i < REINTENTOS; i++) serie.push(http.post(`${BASE_URL}/api/v2/shifts/${I.turno}/sales`, cuerpo, params()));
    const todas = juntas.concat(serie);

    respuestas5xx.add(todas.filter((r) => r.status >= 500).length);
    const ok = todas.filter((r) => r.status === 200);
    const nuevas = ok.filter((r) => r.headers['Idempotent-Replayed'] !== 'true');
    const ids = new Set(ok.map((r) => r.json('id')));
    if (ids.size > 1) duplicadas.add(ids.size - 1);
    if (ids.size >= 1) clavesConVenta.add(1);

    check(null, {
        'una sola venta por Idempotency-Key (mismo id en todas las 200)': () => ids.size === 1,
        'una sola respuesta nueva, las demas replay': () => nuevas.length === 1,
        'todas 200 o 409 (ninguna 5xx)': () => todas.every((r) => r.status === 200 || r.status === 409),
    }, { tipo: 'idempotencia' });
}

export function teardown(s) {
    const vs = http.get(`${BASE_URL}/api/v2/shifts/${I.turno}/sales`, conNombre(s.root, 'GET /api/v2/shifts/{id}/sales'));
    const ventas = vs.status === 200 ? vs.json().length - s.ventasAntes : null;
    console.log(`estado final: ventas nuevas en el turno=${ventas} para ${ITERACIONES} claves`);
    check(null, {
        'ventas nuevas en el turno = claves usadas (ni una duplicada)': () => ventas === ITERACIONES,
    }, { tipo: 'idempotencia' });
}

export const handleSummary = resumen('idempotencia', () => [
    `claves: ${ITERACIONES}, envios por clave: ${PARALELOS} a la vez + ${REINTENTOS} en serie`,
]);
