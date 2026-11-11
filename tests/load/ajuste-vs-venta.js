// Mejora continua v4 (k6, PR 4): el encargado ajusta el stock MIENTRAS los cajeros venden el mismo producto.
// Local 94 de la semilla: producto 94001 con stock chico (60) y un turno abierto. A la vez, durante DURACION:
//   - cajas:     CAJAS cajeros vendiendo 1 unidad sin pausa (POST /shifts/{id}/sales con Idempotency-Key propia);
//   - encargado: alterna SALIDA de 3 (merma) y ENTRADA de 2 (reposicion) con POST /stores/94/stock/adjustment.
// El stock llega a 0 enseguida y cada reposicion se la disputan las ventas y la proxima merma: la carrera es sobre la
// misma fila de inventory_stock por dos caminos distintos (SalesService.deductStock e InventoryService.adjust).
// La regla del backend: una SALIDA o una venta sin stock suficiente se rechaza con 409 (nunca deja stock negativo).
// Checks de negocio:
//   - stock final >= 0 (y nunca se vio negativo en el medio);
//   - stock final = suma de los movimientos del producto (ENTRADA suma, SALIDA resta: ventas incluidas);
//   - ventas del turno = movimientos de SALIDA por venta;
//   - lo que vio el cliente cuadra con el servidor: inicial + 2 x entradas OK - 3 x mermas OK - ventas OK = stock final
//     (se compara en el informe; un DESCUADRE hace fallar la corrida en correr.sh).
// Si falla es un HALLAZGO (stock que no cuadra o negativo = P1): no se arregla en este PR.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, ajuste, venta } from './lib/datos.js';
import { resumen, porEndpoint, SIN_429 } from './lib/resumen.js';

const ventasOk = new Counter('ventas_ok');
const ventasSinStock = new Counter('ventas_sin_stock');
const entradasOk = new Counter('entradas_ok');
const mermasOk = new Counter('mermas_ok');
const mermasSinStock = new Counter('mermas_sin_stock');
const respuestas5xx = new Counter('respuestas_5xx');
const otroEstado = new Counter('respuestas_otro_estado');
const stockNegativo = new Counter('stock_negativo');
const descuadres = new Counter('descuadres');
// estado final del servidor, para cruzarlo con lo que vio el cliente en el informe
const stockFinal = new Counter('stock_final');
const ventasRegistradas = new Counter('ventas_registradas');

const A = ajuste();
const CAJAS = Number(__ENV.CAJAS || 3);
const DURACION = __ENV.DURACION || '1m';
const ENTRADA = 2;
const MERMA = 3;
const VENTA = 'POST /api/v2/shifts/{id}/sales';
const AJUSTE = 'POST /api/v2/stores/{id}/stock/adjustment';
const STOCK = 'GET /api/v2/stores/{id}/stock';
const MOVS = 'GET /api/v2/stores/{id}/stock/movements';
const VENTAS = 'GET /api/v2/shifts/{id}/sales';
const ESPERADOS = http.expectedStatuses(200, 409); // 409 = sin stock suficiente: la respuesta correcta

export const options = {
    scenarios: {
        cajas: { executor: 'constant-vus', vus: CAJAS, duration: DURACION, exec: 'caja' },
        encargado: { executor: 'constant-vus', vus: 1, duration: DURACION, exec: 'encargado' },
        // un mirador que lee el stock cada 2 s: si alguna vez se ve negativo, es hallazgo aunque al final cuadre
        mirador: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '2s', duration: DURACION, preAllocatedVUs: 1, exec: 'mirar' },
    },
    thresholds: Object.assign({
        stock_negativo: ['count==0'],
        descuadres: ['count==0'],
        respuestas_5xx: ['count==0'],
        respuestas_otro_estado: ['count==0'],
        'checks{tipo:ajuste}': ['rate==1'],
    }, SIN_429, porEndpoint(['POST keycloak token', STOCK, MOVS, VENTAS], {}),
    porEndpoint([VENTA, AJUSTE], { [VENTA]: ['p(95)<500'], [AJUSTE]: ['p(95)<500'] })),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    const cajeros = usuarios('cajero', 'carga-a').slice(0, CAJAS).map((u) => login(u.username));
    if (cajeros.length < CAJAS) throw new Error(`Faltan cajeros sinteticos: hay ${cajeros.length} y hacen falta ${CAJAS}`);
    const root = login(usuario('root', 0, 'carga-a').username);
    // stock al empezar (si el ambiente ya corrio este escenario, se parte de lo que quedo)
    const st = http.get(`${BASE_URL}/api/v2/stores/${A.local}/stock`, conNombre(root, STOCK));
    const fila = st.status === 200 ? st.json().find((x) => x.productId === A.producto) : null;
    const vs = http.get(`${BASE_URL}/api/v2/shifts/${A.turno}/sales`, conNombre(root, VENTAS));
    return {
        cajeros, root,
        encargado: login(usuario('encargado', 0, 'carga-a').username),
        stockInicial: fila ? fila.quantity : null,
        ventasAntes: vs.status === 200 ? vs.json().length : 0,
    };
}

function contar(r, ok, sinStock) {
    if (r.status === 200) ok.add(1);
    else if (r.status === 409) sinStock.add(1);
    else if (r.status >= 500) { respuestas5xx.add(1); otroEstado.add(1); } else otroEstado.add(1);
}

export function caja(s) {
    const token = s.cajeros[(exec.vu.idInTest - 1) % s.cajeros.length];
    const params = conNombre(token, VENTA, { 'Idempotency-Key': `ajv-${exec.scenario.startTime}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}` });
    params.responseCallback = ESPERADOS;
    const r = http.post(`${BASE_URL}/api/v2/shifts/${A.turno}/sales`, venta(A.producto, 1), params);
    contar(r, ventasOk, ventasSinStock);
    check(r, { 'venta 200 o 409 (sin stock)': (x) => x.status === 200 || x.status === 409 }, { tipo: 'ajuste' });
}

export function encargado(s) {
    const merma = exec.vu.iterationInScenario % 2 === 0;
    const cuerpo = merma
        ? { productId: A.producto, type: 'SALIDA', quantity: MERMA, reason: 'merma k6', notes: 'sintetico' }
        : { productId: A.producto, type: 'ENTRADA', quantity: ENTRADA, reason: 'reposicion k6', notes: 'sintetico' };
    const params = conNombre(s.encargado, AJUSTE);
    params.responseCallback = ESPERADOS;
    const r = http.post(`${BASE_URL}/api/v2/stores/${A.local}/stock/adjustment`, JSON.stringify(cuerpo), params);
    if (merma) contar(r, mermasOk, mermasSinStock);
    else if (r.status === 200) entradasOk.add(1);
    else contar(r, entradasOk, otroEstado); // una ENTRADA nunca deberia dar 409
    if (r.status === 200 && r.json('quantity') < 0) stockNegativo.add(1);
    check(r, {
        'ajuste 200, o 409 solo en una merma sin stock': (x) => x.status === 200 || (merma && x.status === 409),
    }, { tipo: 'ajuste' });
}

export function mirar(s) {
    const st = http.get(`${BASE_URL}/api/v2/stores/${A.local}/stock`, conNombre(s.root, STOCK));
    const fila = st.status === 200 ? st.json().find((x) => x.productId === A.producto) : null;
    if (fila && fila.quantity < 0) stockNegativo.add(1);
}

export function teardown(s) {
    const st = http.get(`${BASE_URL}/api/v2/stores/${A.local}/stock`, conNombre(s.root, STOCK));
    const fila = st.status === 200 ? st.json().find((x) => x.productId === A.producto) : null;
    const final = fila ? fila.quantity : null;
    if (final !== null && final < 0) stockNegativo.add(1);

    // Movimientos del producto: ENTRADA (y cualquier tipo que no sea SALIDA) suma, SALIDA resta (InventoryService.adjust)
    const mv = http.get(`${BASE_URL}/api/v2/stores/${A.local}/stock/movements`, conNombre(s.root, MOVS));
    const movs = mv.status === 200 ? mv.json().filter((m) => m.productId === A.producto) : [];
    const sumaMovs = movs.reduce((t, m) => t + (m.type === 'SALIDA' ? -m.quantity : m.quantity), 0);
    const salidasPorVenta = movs.filter((m) => m.type === 'SALIDA' && m.source === 'SALE').length;

    const vs = http.get(`${BASE_URL}/api/v2/shifts/${A.turno}/sales`, conNombre(s.root, VENTAS));
    const ventasTurno = vs.status === 200 ? vs.json().length : null;
    const ventasNuevas = ventasTurno === null ? null : ventasTurno - s.ventasAntes;

    if (final !== null) stockFinal.add(final);
    if (ventasNuevas !== null) ventasRegistradas.add(ventasNuevas);
    console.log(`estado final: stock=${final} (al empezar ${s.stockInicial}), suma de movimientos=${sumaMovs}, ` +
        `ventas del turno=${ventasTurno} (nuevas ${ventasNuevas}), salidas por venta=${salidasPorVenta}`);

    const ok = check(null, {
        'stock final >= 0': () => final !== null && final >= 0,
        'stock final = suma de los movimientos del producto': () => final !== null && final === sumaMovs,
        'ventas del turno = salidas por venta': () => ventasTurno !== null && ventasTurno === salidasPorVenta,
    }, { tipo: 'ajuste' });
    if (!ok) descuadres.add(1);
}

// Cruce cliente / servidor: solo se puede hacer con las metricas ya juntadas (handleSummary).
function cruce(r) {
    const v = (n) => (r.metricas[n] ? r.metricas[n].valores.count : 0);
    const inicial = Number(__ENV.STOCK_INICIAL_AJUSTE || A.stock);
    const esperado = inicial + ENTRADA * v('entradas_ok') - MERMA * v('mermas_ok') - v('ventas_ok');
    const final = v('stock_final');
    const ventasCuadran = v('ventas_ok') === v('ventas_registradas');
    const stockCuadra = esperado === final;
    return [
        `cruce cliente/servidor: inicial ${inicial} + ${ENTRADA} x ${v('entradas_ok')} entradas - ${MERMA} x ${v('mermas_ok')} mermas ` +
            `- ${v('ventas_ok')} ventas = ${esperado}; stock final del servidor = ${final} ` +
            `${stockCuadra ? '(cuadra)' : '**DESCUADRE**'}`,
        `ventas OK vistas por el cliente: ${v('ventas_ok')}; ventas nuevas en el turno: ${v('ventas_registradas')} ` +
            `${ventasCuadran ? '(cuadra)' : '**DESCUADRE**'}`,
        `rechazos por falta de stock: ${v('ventas_sin_stock')} ventas y ${v('mermas_sin_stock')} mermas (409, lo esperado)`,
    ];
}

export const handleSummary = resumen('ajuste-vs-venta', cruce);
