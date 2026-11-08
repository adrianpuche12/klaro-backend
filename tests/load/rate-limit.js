// Mejora continua v4 (k6, PR 4): rate limit con los valores de PROD (RateLimitingFilter, backend#39).
// Es el UNICO escenario que corre con los limites reales: correr.sh reinicia el backend con 300 / 100 / 1000 antes
// y lo vuelve a los valores altos despues. Todo sale de la MISMA IP (el contenedor de k6), como las cajas de un
// local detras de un NAT. Cada caja es un usuario con su token, y se mezclan pedidos sin token.
//
// Fase 1 (0 a 3 min), todo a la vez:
//   cajas      CAJAS_POR_LOCAL cajeros a ritmo de mostrador: VENTAS_POR_MIN ventas por minuto cada uno, y por venta
//              el turno activo + quote + venta (3 pedidos). **Ritmo A CONFIRMAR** (decision de Jorge 08-Nov: 5 cajas por
//              local, 2 ventas por minuto cada una). Tienen que recibir 0 respuestas 429.
//   intenso    un usuario a 250/min (por debajo de 300): 0 respuestas 429.
//   pasado     un usuario a 600/min (el doble de su cupo): se corta pasado su cupo, con 429 y Retry-After.
//   anonimos   pedidos sin token a 200/min (el doble de su cupo por IP): se cortan pasado 100/min.
// (gracefulStop de 5 s: ninguna caja de la fase 1 sigue viva cuando empieza la fase 2)
// Fase 2 (desde 3:15, 2 min): 10 usuarios distintos, cada uno a 200/min (lejos de su cupo), que juntos hacen 2.000/min
//   desde la misma IP: el techo de 1.000/min por IP los corta.
//
// Cupo de un bucket (Bucket4j, recarga continua): en T minutos deja pasar como mucho cupo + cupo x T. Los umbrales
// "aceptados <= ..." usan eso con 5 % de margen. Si algo falla es un HALLAZGO (cajas cortadas = un local sin vender).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, empresa, venta } from './lib/datos.js';
import { resumen, porEndpoint } from './lib/resumen.js';

// Valores de PROD (los de la tabla aprobada por Jorge). correr.sh levanta el backend con estos mismos.
const POR_USUARIO = Number(__ENV.RL_USER || 300);
const ANON_POR_IP = Number(__ENV.RL_ANON || 100);
const TECHO_IP = Number(__ENV.RL_TECHO || 1000);

// Ritmo de caja A CONFIRMAR (decision de Jorge 08-Nov: 5 cajas por local, 2 ventas por minuto cada una)
const CAJAS_POR_LOCAL = Number(__ENV.CAJAS_POR_LOCAL || 5);
const VENTAS_POR_MIN = Number(__ENV.VENTAS_POR_MIN || 2);
const PEDIDOS_POR_VENTA = 3; // turno activo + quote + venta

const F1 = 3;     // minutos de la fase 1
const F2 = 2;     // minutos de la fase 2
const INTENSO = 250;
const PASADO = 2 * POR_USUARIO;
const ANON = 2 * ANON_POR_IP;
const TECHO_USUARIOS = 10;
const TECHO_RITMO = 2 * TECHO_IP;  // entre los 10
const MARGEN = 1.05;

const aceptadosPasado = new Counter('aceptados_pasado');
const aceptadosAnon = new Counter('aceptados_anonimos');
const aceptadosTecho = new Counter('aceptados_techo');
const ventasCaja = new Counter('ventas_caja_ok');
const sinRetryAfter = new Counter('respuestas_429_sin_retry_after');
const respuestas5xx = new Counter('respuestas_5xx');

const LOCAL = empresa('carga-a').locales[0];
const STORES = 'GET /api/v2/stores';
const ACTIVO = 'GET /api/v2/shifts/active/{storeId}';
const QUOTE = 'POST /api/v2/shifts/{id}/sales/quote';
const VENTA = 'POST /api/v2/shifts/{id}/sales';

const tope = (cupo, minutos) => Math.floor((cupo + cupo * minutos) * MARGEN);

export const options = {
    scenarios: {
        cajas: { executor: 'constant-vus', vus: CAJAS_POR_LOCAL, duration: `${F1}m`, exec: 'caja', gracefulStop: '5s' },
        intenso: { executor: 'constant-arrival-rate', rate: INTENSO, timeUnit: '1m', duration: `${F1}m`,
            preAllocatedVUs: 3, maxVUs: 10, exec: 'intenso', gracefulStop: '5s' },
        pasado: { executor: 'constant-arrival-rate', rate: PASADO, timeUnit: '1m', duration: `${F1}m`,
            preAllocatedVUs: 3, maxVUs: 10, exec: 'pasado', gracefulStop: '5s' },
        anonimos: { executor: 'constant-arrival-rate', rate: ANON, timeUnit: '1m', duration: `${F1}m`,
            preAllocatedVUs: 2, maxVUs: 10, exec: 'anonimo', gracefulStop: '5s' },
        techo: { executor: 'constant-arrival-rate', rate: TECHO_RITMO, timeUnit: '1m', duration: `${F2}m`, startTime: `${F1}m15s`,
            preAllocatedVUs: 10, maxVUs: 30, exec: 'techo' },
    },
    thresholds: Object.assign({
        // por debajo de su cupo: ni un 429
        'http_reqs{scenario:cajas,status:429}': ['count==0'],
        'http_reqs{scenario:intenso,status:429}': ['count==0'],
        // por encima: se cortan, y no pasa mas de lo que permite el cupo
        'http_reqs{scenario:pasado,status:429}': ['count>0'],
        'http_reqs{scenario:anonimos,status:429}': ['count>0'],
        'http_reqs{scenario:techo,status:429}': ['count>0'],
        aceptados_pasado: [`count<=${tope(POR_USUARIO, F1)}`],
        aceptados_anonimos: [`count<=${tope(ANON_POR_IP, F1)}`],
        aceptados_techo: [`count<=${tope(TECHO_IP, F2)}`],
        'respuestas_429_sin_retry_after': ['count==0'],
        respuestas_5xx: ['count==0'],
        'checks{tipo:rate}': ['rate==1'],
    }, porEndpoint(['POST keycloak token', STORES, ACTIVO, QUOTE, VENTA], {})),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const cajeros = usuarios('cajero', 'carga-a').slice(0, CAJAS_POR_LOCAL).map((u) => login(u.username));
    if (cajeros.length < CAJAS_POR_LOCAL) throw new Error(`Faltan cajeros: hay ${cajeros.length} y hacen falta ${CAJAS_POR_LOCAL}`);
    const techo = usuarios('cajero', 'carga-b').slice(0, TECHO_USUARIOS).map((u) => login(u.username));
    if (techo.length < TECHO_USUARIOS) throw new Error(`Faltan cajeros de carga-b: hay ${techo.length}`);
    return {
        cajeros,
        techo,
        intenso: login(usuario('root', 0, 'carga-c').username),
        pasado: login(usuario('root', 0, 'carga-b').username),
    };
}

function retryAfter(r) {
    if (r.status !== 429) return;
    const v = Number(r.headers['Retry-After']);
    const ok = check(r, { '429 con Retry-After entre 1 y 60 s': () => Number.isInteger(v) && v >= 1 && v <= 60 }, { tipo: 'rate' });
    if (!ok) sinRetryAfter.add(1);
}

function pedir(url, token, nombre, esperados) {
    const p = token ? conNombre(token, nombre) : { tags: { name: `${nombre} (sin token)` } };
    p.responseCallback = http.expectedStatuses(...esperados);
    const r = http.get(url, p);
    if (r.status >= 500) respuestas5xx.add(1);
    retryAfter(r);
    return r;
}

// Una caja de mostrador: turno activo, quote y venta; despues espera hasta completar su ritmo
export function caja(s) {
    const inicio = Date.now();
    const token = s.cajeros[(exec.vu.idInTest - 1) % s.cajeros.length];
    pedir(`${BASE_URL}/api/v2/shifts/active/${LOCAL.id}`, token, ACTIVO, [200]);
    const cuerpo = venta(LOCAL.productos[exec.vu.idInTest % LOCAL.productos.length], 1);
    const q = http.post(`${BASE_URL}/api/v2/shifts/${LOCAL.turno}/sales/quote`, cuerpo, conNombre(token, QUOTE));
    const vp = conNombre(token, VENTA, { 'Idempotency-Key': `rl-${exec.scenario.startTime}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}` });
    const v = http.post(`${BASE_URL}/api/v2/shifts/${LOCAL.turno}/sales`, cuerpo, vp);
    for (const r of [q, v]) { if (r.status >= 500) respuestas5xx.add(1); retryAfter(r); }
    if (v.status === 200) ventasCaja.add(1);
    check(v, { 'la caja vende (200) a ritmo de mostrador': (x) => x.status === 200 }, { tipo: 'rate' });
    const resto = 60 / VENTAS_POR_MIN - (Date.now() - inicio) / 1000;
    if (resto > 0) sleep(resto);
}

export function intenso(s) {
    const r = pedir(`${BASE_URL}/api/v2/stores`, s.intenso, STORES, [200]);
    check(r, { 'usuario a 250/min: nunca 429': (x) => x.status === 200 }, { tipo: 'rate' });
}

export function pasado(s) {
    const r = pedir(`${BASE_URL}/api/v2/stores`, s.pasado, STORES, [200, 429]);
    if (r.status === 200) aceptadosPasado.add(1);
}

export function anonimo() {
    // sin token: el filtro lo cuenta por IP y despues Spring Security responde 401
    const r = pedir(`${BASE_URL}/api/v2/stores`, null, STORES, [401, 429]);
    if (r.status === 401) aceptadosAnon.add(1);
    check(r, { 'sin token: 401 o 429, nunca 200': (x) => x.status === 401 || x.status === 429 }, { tipo: 'rate' });
}

export function techo(s) {
    const token = s.techo[exec.scenario.iterationInTest % s.techo.length];
    const r = pedir(`${BASE_URL}/api/v2/stores`, token, STORES, [200, 429]);
    if (r.status === 200) aceptadosTecho.add(1);
}

function porMin(r, metrica, minutos) {
    const m = r.metricas[metrica];
    return m ? (m.valores.count / minutos).toFixed(0) : '-';
}

export const handleSummary = resumen('rate-limit', (r) => {
    const c = (n) => (r.metricas[n] ? r.metricas[n].valores.count : 0);
    return [
        `limites del backend en esta corrida (los de PROD): ${POR_USUARIO}/min por usuario, ${ANON_POR_IP}/min por IP sin token, techo ${TECHO_IP}/min por IP`,
        `ritmo de caja (**a confirmar**): ${CAJAS_POR_LOCAL} cajas por local x ${VENTAS_POR_MIN} ventas/min x ${PEDIDOS_POR_VENTA} pedidos = ` +
            `${CAJAS_POR_LOCAL * VENTAS_POR_MIN * PEDIDOS_POR_VENTA} pedidos/min del local; ventas OK de las cajas: ${c('ventas_caja_ok')}`,
        `cajas: 429 = ${c('http_reqs{scenario:cajas,status:429}')} · intenso (${INTENSO}/min): 429 = ${c('http_reqs{scenario:intenso,status:429}')}`,
        `pasado (${PASADO}/min): aceptados ${c('aceptados_pasado')} (tope ${tope(POR_USUARIO, F1)}), ${porMin(r, 'aceptados_pasado', F1)}/min; ` +
            `429 = ${c('http_reqs{scenario:pasado,status:429}')}`,
        `sin token (${ANON}/min): aceptados ${c('aceptados_anonimos')} (tope ${tope(ANON_POR_IP, F1)}), ${porMin(r, 'aceptados_anonimos', F1)}/min; ` +
            `429 = ${c('http_reqs{scenario:anonimos,status:429}')}`,
        `techo (${TECHO_USUARIOS} usuarios, ${TECHO_RITMO}/min juntos): aceptados ${c('aceptados_techo')} (tope ${tope(TECHO_IP, F2)}), ` +
            `${porMin(r, 'aceptados_techo', F2)}/min; 429 = ${c('http_reqs{scenario:techo,status:429}')}`,
    ];
});
