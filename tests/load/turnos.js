// Mejora continua v4 (k6, PR 4): dos cajas abren el turno del MISMO local a la vez (ShiftService.openShift:
// lock del local + indice unico parcial ux_shift_open, V14).
// Local 93 de la semilla, que arranca SIN turno abierto. En cada iteracion, PARALELOS cajeros distintos mandan
// POST /stores/93/shifts al mismo tiempo (http.batch); despues se mira el turno activo y el root lo cierra.
// Checks de negocio: EXACTAMENTE una apertura OK por ronda (200) y el resto 409 "ya hay un turno abierto";
// el turno activo es el que se abrio; ninguna 5xx. Al final no queda ningun turno abierto.
// Si falla es un HALLAZGO (dos turnos abiertos en un local = P1: las ventas y el cierre de caja se parten).
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, turnos } from './lib/datos.js';
import { resumen, porEndpoint, SIN_429 } from './lib/resumen.js';

const turnosDobles = new Counter('turnos_dobles');          // rondas con mas de una apertura OK
const aperturasPerdidas = new Counter('aperturas_perdidas'); // rondas sin ninguna apertura OK (todas 409 sin turno)
const turnosAbiertos = new Counter('turnos_abiertos_ok');
const respuestas5xx = new Counter('respuestas_5xx');

const T = turnos();
const PARALELOS = Number(__ENV.PARALELOS || 2);
const RONDAS = Number(__ENV.RONDAS || 100);
const ABRIR = 'POST /api/v2/stores/{id}/shifts';
const ACTIVO = 'GET /api/v2/shifts/active/{storeId}';
const CERRAR = 'PUT /api/v2/shifts/{id}/close';
const ESPERADOS = http.expectedStatuses(200, 409); // 409 = ya hay un turno abierto: la respuesta correcta para la segunda

export const options = {
    scenarios: {
        // un solo VU: cada ronda espera a que se cierre el turno de la anterior; la concurrencia esta dentro del batch
        aperturas: { executor: 'shared-iterations', vus: 1, iterations: RONDAS, maxDuration: '5m' },
    },
    thresholds: Object.assign({
        turnos_dobles: ['count==0'],
        aperturas_perdidas: ['count==0'],
        respuestas_5xx: ['count==0'],
        'checks{tipo:turnos}': ['rate==1'],
    }, SIN_429, porEndpoint(['POST keycloak token', ACTIVO, CERRAR], {}), porEndpoint([ABRIR], { [ABRIR]: ['p(95)<500'] })),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const cajeros = usuarios('cajero', 'carga-a').slice(0, PARALELOS).map((u) => login(u.username));
    if (cajeros.length < PARALELOS) throw new Error(`Faltan cajeros sinteticos: hay ${cajeros.length} y hacen falta ${PARALELOS}`);
    const root = login(usuario('root', 0, 'carga-a').username);
    // Si una corrida anterior sobre el mismo ambiente dejo un turno abierto, se cierra antes de empezar
    const previo = http.get(`${BASE_URL}/api/v2/shifts/active/${T.local}`, conNombre(root, ACTIVO));
    if (previo.status === 200) http.put(`${BASE_URL}/api/v2/shifts/${previo.json('id')}/close`, null, conNombre(root, CERRAR));
    return { cajeros, root };
}

export default function (s) {
    const pedidos = s.cajeros.map((token) => {
        const p = conNombre(token, ABRIR);
        p.responseCallback = ESPERADOS;
        return { method: 'POST', url: `${BASE_URL}/api/v2/stores/${T.local}/shifts`, body: JSON.stringify({ username: 'k6' }), params: p };
    });
    const r = http.batch(pedidos);
    respuestas5xx.add(r.filter((x) => x.status >= 500).length);
    const ok = r.filter((x) => x.status === 200);
    if (ok.length > 1) turnosDobles.add(ok.length - 1);
    if (ok.length === 0) aperturasPerdidas.add(1);
    turnosAbiertos.add(ok.length);

    const activo = http.get(`${BASE_URL}/api/v2/shifts/active/${T.local}`, conNombre(s.root, ACTIVO));
    const idActivo = activo.status === 200 ? activo.json('id') : null;

    check(null, {
        'una sola apertura OK por ronda': () => ok.length === 1,
        'el resto 409 (ya hay un turno abierto), ninguna 5xx': () => r.every((x) => x.status === 200 || x.status === 409),
        'el turno activo es el que se abrio': () => ok.length === 1 && idActivo === ok[0].json('id'),
    }, { tipo: 'turnos' });

    // Se cierran todos los que se hayan abierto (si hubo dos, tambien el segundo), para la ronda siguiente
    const ids = new Set(ok.map((x) => x.json('id')));
    if (idActivo !== null) ids.add(idActivo);
    for (const id of ids) http.put(`${BASE_URL}/api/v2/shifts/${id}/close`, null, conNombre(s.root, CERRAR));
    if (exec.scenario.iterationInTest % 25 === 0) console.log(`ronda ${exec.scenario.iterationInTest}: ${ok.length} apertura(s) OK`);
}

export function teardown(s) {
    const activo = http.get(`${BASE_URL}/api/v2/shifts/active/${T.local}`, conNombre(s.root, ACTIVO));
    console.log(`estado final: turno activo en el local ${T.local} = ${activo.status === 200 ? activo.json('id') : 'ninguno'}`);
    check(activo, { 'al final no queda ningun turno abierto (204)': (x) => x.status === 204 }, { tipo: 'turnos' });
}

export const handleSummary = resumen('turnos', () => [
    `rondas: ${RONDAS}, aperturas simultaneas por ronda: ${PARALELOS} (cajeros distintos, mismo local ${T.local})`,
]);
