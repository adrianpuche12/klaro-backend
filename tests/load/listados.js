// Mejora continua v4 (k6, PR 4 parte 2): listados con un ano de datos.
// /api/v3/operations (OperationsV3Service.getOperations) junta TODAS las operaciones del rango en memoria
// (ventas, cierres, pagos, gastos...), las ordena y recien despues corta la pagina; `size` no tiene tope
// (hipotesis 3 de la propuesta de k6). Tambien /api/v3/operations/summary, /stores/{id}/sales y /stock/movements.
// Rampa de 1 a 6 VUs: el contador y el encargado de las 3 empresas (~22.000 ventas por empresa en un ano).
// Umbrales (tecnicos, A CONFIRMAR): p95 < 1 s por endpoint y 0 respuestas 5xx. Ademas se mide cuanto tarda
// la pagina 0 de 20 de un ano contra la de 30 dias: si cuesta casi lo mismo que pedir todo, pagina en memoria.
// Lo que falle es un HALLAZGO (Listados que aguantan un ano de ventas), no se arregla aca.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuario, empresa } from './lib/datos.js';
import { resumen, porEndpoint } from './lib/resumen.js';

const respuestas5xx = new Counter('respuestas_5xx');
const bytesSinTope = new Trend('bytes_operaciones_size_5000');

const EMPRESAS = ['carga-a', 'carga-b', 'carga-c'];
const OPS_30 = 'GET /api/v3/operations (30 dias pagina 0 de 20)';
const OPS_365 = 'GET /api/v3/operations (365 dias pagina 0 de 20)';
const OPS_365_P50 = 'GET /api/v3/operations (365 dias pagina 50 de 20)';
const OPS_SIN_TOPE = 'GET /api/v3/operations (365 dias size 5000)';
const RESUMEN = 'GET /api/v3/operations/summary (365 dias)';
const VENTAS = 'GET /api/v2/stores/{id}/sales (90 dias)';
const MOVS = 'GET /api/v2/stores/{id}/stock/movements';
const TODOS = [OPS_30, OPS_365, OPS_365_P50, OPS_SIN_TOPE, RESUMEN, VENTAS, MOVS];
const UNO_SEG = Object.fromEntries(TODOS.map((n) => [n, ['p(95)<1000']]));

export const options = {
    scenarios: {
        listados: {
            executor: 'ramping-vus', startVUs: 1,
            stages: [{ duration: '1m', target: 3 }, { duration: '1m', target: 6 }, { duration: '1m', target: 6 }],
            gracefulStop: '60s',
        },
    },
    thresholds: Object.assign({
        respuestas_5xx: ['count==0'],
        bytes_operaciones_size_5000: ['max>=0'],
    }, porEndpoint(['POST keycloak token'], {}), porEndpoint(TODOS, UNO_SEG)),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const t = {};
    for (const e of EMPRESAS) {
        t[e] = { contador: login(usuario('contador', 0, e).username), encargado: login(usuario('encargado', 0, e).username) };
    }
    return t;
}

function hace(dias) {
    return new Date(Date.now() - dias * 86400000).toISOString().slice(0, 10);
}

function get(token, ruta, nom, extra = {}) {
    const p = Object.assign(conNombre(token, nom), { timeout: '120s' }, extra);
    const r = http.get(`${BASE_URL}${ruta}`, p);
    if (r.status >= 500 || r.status === 0) respuestas5xx.add(1);
    check(r, { [`${nom} 200`]: (x) => x.status === 200 });
    return r;
}

export default function (t) {
    const emp = EMPRESAS[exec.vu.idInTest % EMPRESAS.length];
    const local = empresa(emp).locales[0].id;
    const { contador, encargado } = t[emp];
    const n = exec.vu.iterationInScenario;
    const hoy = hace(0);
    // el contador (OPERATIONS) mira operaciones; el encargado, ventas y movimientos de su local
    switch (n % 4) {
        case 0:
            get(contador, `/api/v3/operations?from=${hace(30)}&to=${hoy}&storeId=${local}&page=0&size=20`, OPS_30);
            get(contador, `/api/v3/operations?from=${hace(365)}&to=${hoy}&storeId=${local}&page=0&size=20`, OPS_365);
            break;
        case 1:
            get(contador, `/api/v3/operations?from=${hace(365)}&to=${hoy}&storeId=${local}&page=50&size=20`, OPS_365_P50);
            get(contador, `/api/v3/operations/summary?from=${hace(365)}&to=${hoy}&storeId=${local}`, RESUMEN);
            break;
        case 2: {
            // sin tope: un cliente (o un error del frontend) puede pedir miles de una vez
            const r = get(contador, `/api/v3/operations?from=${hace(365)}&to=${hoy}&storeId=${local}&page=0&size=5000`,
                OPS_SIN_TOPE, { responseType: 'binary' });
            if (r.status === 200 && r.body) bytesSinTope.add(r.body.byteLength);
            break;
        }
        default:
            get(encargado, `/api/v2/stores/${local}/sales?from=${hace(90)}&to=${hoy}`, VENTAS);
            get(encargado, `/api/v2/stores/${local}/stock/movements`, MOVS, { responseType: 'none' });
    }
    sleep(1);
}

export const handleSummary = resumen('listados', (r) => {
    const p95 = (n) => {
        const m = r.metricas[`http_req_duration{name:${n}}`];
        return m ? Math.round(m.valores['p(95)']) : null;
    };
    const b = r.metricas.bytes_operaciones_size_5000;
    const a = p95(OPS_30);
    const c = p95(OPS_365);
    return [
        `pagina 0 de 20: p95 ${a} ms con 30 dias contra ${c} ms con 365 dias` +
            (a && c ? ` (x${(c / a).toFixed(1)}: si crece con el rango aunque la pagina sea igual, pagina en memoria)` : ''),
        `size=5000 sin tope: respuesta mediana ${b ? Math.round(b.valores.med / 1024) : '-'} KiB, max ${b ? Math.round(b.valores.max / 1024) : '-'} KiB`,
    ];
});
