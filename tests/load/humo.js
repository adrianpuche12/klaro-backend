// Mejora continua v4 (k6): humo de carga. 2 VUs durante 1 minuto por los flujos principales de encargado,
// cajero y contador de la empresa A (local 11, con un ano de historial).
// Umbrales de la propuesta (tecnicos, a confirmar con la linea de base): http_req_failed < 1 % y 0 respuestas 429.
// Los listados pesados (operaciones, informes de un ano) son de listados.js / informes.js (PR 4).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuario, empresa, venta } from './lib/datos.js';
import { resumen, porEndpoint, SIN_429 } from './lib/resumen.js';

const ENDPOINTS = ['GET /api/v2/stores', 'GET /api/v2/stores/{id}/products', 'GET /api/v2/stores/{id}/stock',
    'GET /api/v2/shifts/active/{storeId}', 'GET /api/v2/dashboard', 'GET /api/v2/stores/{id}/sales (7 dias)',
    'POST /api/v2/shifts/{id}/sales/quote', 'POST /api/v2/shifts/{id}/sales', 'POST keycloak token'];

export const options = {
    scenarios: {
        humo: { executor: 'constant-vus', vus: Number(__ENV.VUS || 2), duration: __ENV.DURACION || '1m' },
    },
    thresholds: Object.assign({
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
    }, SIN_429, porEndpoint(ENDPOINTS)),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const A = empresa('carga-a');
const LOCAL = A.locales[0];

export function setup() {
    return {
        encargado: login(usuario('encargado', 0, 'carga-a').username),
        cajero: login(usuario('cajero', 0, 'carga-a').username),
        contador: login(usuario('contador', 0, 'carga-a').username),
    };
}

function get(token, ruta, nombre) {
    const r = http.get(`${BASE_URL}${ruta}`, conNombre(token, nombre));
    check(r, { [`${nombre} 200`]: (x) => x.status === 200 });
    return r;
}

function hace(dias) {
    const d = new Date(Date.now() - dias * 86400000);
    return d.toISOString().slice(0, 10);
}

export default function (t) {
    const n = exec.scenario.iterationInTest;
    switch (n % 3) {
        case 0: // encargado: catalogo, stock, turno, panel e historial de la semana
            get(t.encargado, '/api/v2/stores', 'GET /api/v2/stores');
            get(t.encargado, `/api/v2/stores/${LOCAL.id}/products`, 'GET /api/v2/stores/{id}/products');
            get(t.encargado, `/api/v2/stores/${LOCAL.id}/stock`, 'GET /api/v2/stores/{id}/stock');
            get(t.encargado, `/api/v2/shifts/active/${LOCAL.id}`, 'GET /api/v2/shifts/active/{storeId}');
            get(t.encargado, '/api/v2/dashboard', 'GET /api/v2/dashboard');
            get(t.encargado, `/api/v2/stores/${LOCAL.id}/sales?from=${hace(7)}&to=${hace(0)}`, 'GET /api/v2/stores/{id}/sales (7 dias)');
            break;
        case 1: { // cajero: turno activo, cotizacion y venta con Idempotency-Key (como el POS de frontend#48)
            const turno = get(t.cajero, `/api/v2/shifts/active/${LOCAL.id}`, 'GET /api/v2/shifts/active/{storeId}');
            const shiftId = turno.status === 200 ? turno.json('id') : LOCAL.turno;
            const cuerpo = venta(LOCAL.productos[n % LOCAL.productos.length], 1);
            const q = http.post(`${BASE_URL}/api/v2/shifts/${shiftId}/sales/quote`, cuerpo,
                conNombre(t.cajero, 'POST /api/v2/shifts/{id}/sales/quote'));
            check(q, { 'quote 200': (x) => x.status === 200 });
            const v = http.post(`${BASE_URL}/api/v2/shifts/${shiftId}/sales`, cuerpo,
                conNombre(t.cajero, 'POST /api/v2/shifts/{id}/sales', { 'Idempotency-Key': `humo-${exec.scenario.startTime}-${n}` }));
            check(v, { 'venta 200': (x) => x.status === 200 });
            break;
        }
        default: // contador: panel y locales
            get(t.contador, '/api/v2/dashboard', 'GET /api/v2/dashboard');
            get(t.contador, '/api/v2/stores', 'GET /api/v2/stores');
    }
    sleep(1);
}

export const handleSummary = resumen('humo');
