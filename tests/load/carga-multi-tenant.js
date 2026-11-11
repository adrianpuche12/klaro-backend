// Mejora continua v4 (k6, PR 4 parte 2): varias empresas a la vez y CERO fugas entre empresas.
// Rampa de 10 a 50 VUs repartidos en las 3 empresas sinteticas (cajeros y encargados de cada una): locales,
// ventas de la semana, quote + venta en el turno abierto de su local, panel, y en cada vuelta un intento de leer
// un local o un turno de OTRA empresa (tiene que dar 403/404, nunca 200).
// Checks de aislamiento: todo lo que vuelve es de la empresa del token (ids de locales, local y usuario de cada
// venta). Una sola fuga es P1. Se busca tambien el codo: CPU, heap y espera del pool Hikari en el informe.
// Umbrales (tecnicos, A CONFIRMAR): 0 fugas, 0 respuestas 5xx, p95 < 1 s en cada endpoint.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, empresa, carrera, idempotencia, turnos, ajuste, venta } from './lib/datos.js';
import { resumen, porEndpoint } from './lib/resumen.js';

const fugas = new Counter('fugas_entre_empresas');
const respuestas5xx = new Counter('respuestas_5xx');
const ventasOk = new Counter('ventas_ok');

const SLUGS = ['carga-a', 'carga-b', 'carga-c'];
const LETRA = { 'carga-a': 'a', 'carga-b': 'b', 'carga-c': 'c' };
const E = {};
for (const s of SLUGS) {
    const ids = empresa(s).locales.map((l) => l.id);
    // la empresa A tiene ademas los locales de los escenarios de carreras (91 a 94)
    if (s === 'carga-a') ids.push(carrera().local, idempotencia().local, turnos().local, ajuste().local);
    E[s] = { ids, local: empresa(s).locales[0] };
}

const STORES = 'GET /api/v2/stores';
const VENTAS = 'GET /api/v2/stores/{id}/sales (7 dias)';
const QUOTE = 'POST /api/v2/shifts/{id}/sales/quote';
const VENTA = 'POST /api/v2/shifts/{id}/sales';
const PANEL = 'GET /api/v2/dashboard';
const AJENO_STOCK = 'GET /api/v2/stores/{id}/stock (de otra empresa)';
const AJENO_TURNO = 'GET /api/v2/shifts/{id}/sales (de otra empresa)';
const NOMBRES = [STORES, VENTAS, QUOTE, VENTA, PANEL, AJENO_STOCK, AJENO_TURNO];

export const options = {
    scenarios: {
        empresas: {
            executor: 'ramping-vus', startVUs: 10,
            stages: [{ duration: '1m', target: 10 }, { duration: '2m', target: 50 }, { duration: '1m', target: 50 }],
            gracefulStop: '30s',
        },
    },
    thresholds: Object.assign({
        fugas_entre_empresas: ['count==0'],
        respuestas_5xx: ['count==0'],
        'checks{tipo:aislamiento}': ['rate==1'],
    }, porEndpoint(['POST keycloak token'], {}), porEndpoint(NOMBRES, Object.fromEntries(NOMBRES.map((n) => [n, ['p(95)<1000']])))),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const t = {};
    for (const s of SLUGS) {
        t[s] = {
            cajeros: usuarios('cajero', s).map((u) => login(u.username)),
            encargado: login(usuario('encargado', 0, s).username),
        };
    }
    return t;
}

function hace(dias) {
    return new Date(Date.now() - dias * 86400000).toISOString().slice(0, 10);
}

function pedir(metodo, token, ruta, nom, cuerpo, extra = {}) {
    const p = conNombre(token, nom, extra);
    if (nom === AJENO_STOCK || nom === AJENO_TURNO) p.responseCallback = http.expectedStatuses(403, 404);
    const r = metodo === 'GET' ? http.get(`${BASE_URL}${ruta}`, p) : http.post(`${BASE_URL}${ruta}`, cuerpo, p);
    if (r.status >= 500 || r.status === 0) respuestas5xx.add(1);
    return r;
}

function aislado(nombreCheck, ok) {
    if (!ok) fugas.add(1);
    check(null, { [nombreCheck]: () => ok }, { tipo: 'aislamiento' });
}

export default function (t) {
    const vu = exec.vu.idInTest;
    const n = exec.vu.iterationInScenario;
    const slug = SLUGS[vu % SLUGS.length];
    const otra = SLUGS[(vu + 1) % SLUGS.length];
    const propia = E[slug];
    const cajero = t[slug].cajeros[vu % t[slug].cajeros.length];
    const token = vu % 5 === 0 ? t[slug].encargado : cajero;

    switch (n % 5) {
        case 0: {
            const r = pedir('GET', token, '/api/v2/stores', STORES);
            const ids = r.status === 200 ? r.json().map((x) => x.id) : [];
            aislado('locales: solo los de mi empresa', r.status === 200 && ids.every((id) => propia.ids.includes(id)));
            break;
        }
        case 1: {
            const r = pedir('GET', token, `/api/v2/stores/${propia.local.id}/sales?from=${hace(7)}&to=${hace(0)}`, VENTAS);
            const lista = r.status === 200 ? r.json() : [];
            aislado('ventas: todas de mi local y de usuarios de mi empresa', r.status === 200 && lista.every((v) =>
                v.storeId === propia.local.id && (!v.username || v.username.endsWith(`.${LETRA[slug]}`))));
            break;
        }
        case 2: {
            const cuerpo = venta(propia.local.productos[n % propia.local.productos.length], 1);
            const q = pedir('POST', cajero, `/api/v2/shifts/${propia.local.turno}/sales/quote`, QUOTE, cuerpo);
            const v = pedir('POST', cajero, `/api/v2/shifts/${propia.local.turno}/sales`, VENTA, cuerpo,
                { 'Idempotency-Key': `mt-${exec.scenario.startTime}-${vu}-${n}` });
            if (v.status === 200) ventasOk.add(1);
            check(v, { 'venta 200': (x) => x.status === 200 && q.status === 200 });
            break;
        }
        case 3: {
            // intentos sobre OTRA empresa con el token del encargado (tiene INVENTORY y ventas en sus locales:
            // un 403/404 aca es aislamiento entre empresas, no falta de permiso del modulo). Nunca 200.
            const enc = t[slug].encargado;
            const s = pedir('GET', enc, `/api/v2/stores/${E[otra].local.id}/stock`, AJENO_STOCK);
            aislado('stock de otra empresa: 403/404', s.status === 403 || s.status === 404);
            const v = pedir('GET', enc, `/api/v2/shifts/${E[otra].local.turno}/sales`, AJENO_TURNO);
            aislado('ventas de un turno de otra empresa: 403/404', v.status === 403 || v.status === 404);
            break;
        }
        default: {
            const r = pedir('GET', t[slug].encargado, '/api/v2/dashboard', PANEL); // el cajero no tiene DASHBOARD
            check(r, { 'panel 200': (x) => x.status === 200 });
        }
    }
    sleep(0.5);
}

export const handleSummary = resumen('carga-multi-tenant', (r) => {
    const c = (m) => (r.metricas[m] ? r.metricas[m].valores.count : 0);
    return [
        'rampa de 10 a 50 VUs repartidos en 3 empresas (cajeros y encargados), mezcla de lecturas, ventas e intentos cruzados',
        `fugas entre empresas: ${c('fugas_entre_empresas')} · ventas OK: ${c('ventas_ok')}`,
    ];
});
