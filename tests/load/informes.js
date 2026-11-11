// Mejora continua v4 (k6, PR 4 parte 2): informes de ventas de 30, 90 y 365 dias EN PARALELO.
// /api/v3/reports/sales y /api/v3/reports/products-sold (ReportsController): se arman en memoria y no tienen tope
// de rango (hipotesis 2 de la propuesta de k6). Rampa de 1 a 5 VUs, cada uno el root o el encargado de una de
// las 3 empresas (un ano de historial: ~22.000 ventas por empresa). Formatos PDF y XLSX.
// Se mira la memoria (heap), la CPU y el pool Hikari del backend en el informe (correr.sh).
// Umbrales (tecnicos, A CONFIRMAR): 0 respuestas 5xx; informe de 365 dias p95 < 10 s; 30 dias p95 < 3 s.
// Si alguno falla es un HALLAZGO: va como propuesta (Listados que aguantan un ano de ventas), no se arregla aca.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuario, empresa } from './lib/datos.js';
import { resumen, porEndpoint } from './lib/resumen.js';

const respuestas5xx = new Counter('respuestas_5xx');
const bytesInforme = new Trend('bytes_informe_365');

const EMPRESAS = ['carga-a', 'carga-b', 'carga-c'];
const RANGOS = [30, 90, 365];
const nombre = (tipo, dias, fmt) => `GET /api/v3/reports/${tipo} (${dias} dias ${fmt})`;
const TODOS = [];
for (const t of ['sales', 'products-sold']) for (const d of RANGOS) for (const f of ['PDF', 'XLSX']) TODOS.push(nombre(t, d, f));

const reales = {};
for (const n of TODOS) {
    if (n.includes('(365 dias')) reales[n] = ['p(95)<10000'];
    if (n.includes('(30 dias')) reales[n] = ['p(95)<3000'];
}

export const options = {
    scenarios: {
        informes: {
            executor: 'ramping-vus', startVUs: 1,
            stages: [{ duration: '1m', target: 3 }, { duration: '1m', target: 5 }, { duration: '1m', target: 5 }],
            gracefulStop: '60s',
        },
    },
    thresholds: Object.assign({
        respuestas_5xx: ['count==0'],
        bytes_informe_365: ['max>=0'],
    }, porEndpoint(['POST keycloak token'], {}), porEndpoint(TODOS, reales)),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const tokens = {};
    for (const e of EMPRESAS) {
        tokens[e] = [login(usuario('root', 0, e).username), login(usuario('encargado', 0, e).username)];
    }
    return tokens;
}

function hace(dias) {
    return new Date(Date.now() - dias * 86400000).toISOString().slice(0, 10);
}

export default function (t) {
    const vu = exec.vu.idInTest;
    const n = exec.vu.iterationInScenario;
    const emp = EMPRESAS[vu % EMPRESAS.length];
    const esRoot = n % 2 === 0;
    const token = t[emp][esRoot ? 0 : 1];
    // el root pide todos los locales; el encargado tiene locales acotados y pide el suyo
    const local = esRoot ? '' : `&storeId=${empresa(emp).locales[0].id}`;
    const tipo = n % 3 === 2 ? 'products-sold' : 'sales';
    const dias = RANGOS[(vu + n) % RANGOS.length];
    const fmt = Math.floor(n / 2) % 2 === 0 ? 'PDF' : 'XLSX';
    const nom = nombre(tipo, dias, fmt);
    const params = conNombre(token, nom);
    params.timeout = '120s';
    params.responseType = 'binary'; // solo para medir el tamano; el cuerpo no se guarda en ningun lado
    const r = http.get(`${BASE_URL}/api/v3/reports/${tipo}?from=${hace(dias)}&to=${hace(0)}&format=${fmt}${local}`, params);
    if (r.status >= 500 || r.status === 0) respuestas5xx.add(1);
    if (dias === 365 && r.status === 200 && r.body) bytesInforme.add(r.body.byteLength);
    check(r, { 'informe 200': (x) => x.status === 200 });
    sleep(1);
}

export const handleSummary = resumen('informes', (r) => {
    const b = r.metricas.bytes_informe_365;
    return [
        'rampa de 1 a 5 VUs en paralelo (root y encargado de 3 empresas), informes de 30, 90 y 365 dias en PDF y XLSX',
        `tamano de los informes de 365 dias: mediana ${b ? Math.round(b.valores.med / 1024) : '-'} KiB, max ${b ? Math.round(b.valores.max / 1024) : '-'} KiB`,
    ];
});
