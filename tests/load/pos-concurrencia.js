// Mejora continua v4 (k6): varias cajas vendiendo A LA VEZ el mismo producto con stock limitado.
// 5 cajeros (constant-vus, 3 min) sobre el local 91 (producto 91001 con stock 50 de la semilla y un turno abierto),
// cada uno con quote + venta con Idempotency-Key propia, sin pausa (para forzar la carrera sobre la fila de stock).
// Checks de negocio: stock final >= 0 y EXACTAMENTE tantas ventas OK como stock inicial; el resto 409 y ninguna 5xx.
// Umbrales de la propuesta (tecnicos, a confirmar con la linea de base): venta p95 < 500 ms, quote p95 < 200 ms.
// Si falla un check de carrera es un HALLAZGO (stock negativo o venta de mas = P1): no se arregla en este PR.
// Ojo: sin pausa, los "pedidos por minuto por cajero" de este escenario son un techo, no el ritmo de un cajero real.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { login, conNombre, BASE_URL } from './lib/auth.js';
import { usuarios, usuario, carrera, venta } from './lib/datos.js';
import { resumen, porEndpoint, porMinutoPorVu, SIN_429 } from './lib/resumen.js';

const ventasOk = new Counter('ventas_ok');
const ventasSinStock = new Counter('ventas_sin_stock');
const ventasOtro = new Counter('ventas_otro_estado');
const respuestas5xx = new Counter('respuestas_5xx');
const stockNegativo = new Counter('stock_negativo');

const C = carrera();
const VUS = Number(__ENV.VUS || 5);
const QUOTE = 'POST /api/v2/shifts/{id}/sales/quote';
const VENTA = 'POST /api/v2/shifts/{id}/sales';
const ESPERADOS = http.expectedStatuses(200, 409); // el 409 del que se queda sin stock es la respuesta correcta

export const options = {
    scenarios: {
        cajas: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURACION || '3m' },
    },
    thresholds: Object.assign({
        ventas_ok: [`count==${C.stock}`],
        stock_negativo: ['count==0'],
        respuestas_5xx: ['count==0'],
        'checks{tipo:carrera}': ['rate==1'],
    }, SIN_429, porEndpoint(['POST keycloak token', 'GET /api/v2/stores/{id}/stock', 'GET /api/v2/shifts/{id}/sales'], {}),
    porEndpoint([VENTA, QUOTE], { [VENTA]: ['p(95)<500'], [QUOTE]: ['p(95)<200'] })),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    // cajeros distintos de la empresa A (uno por VU), y el root para mirar el estado final
    const cajeros = usuarios('cajero', 'carga-a').slice(0, VUS).map((u) => login(u.username));
    if (cajeros.length < VUS) throw new Error(`Faltan cajeros sinteticos: hay ${cajeros.length} y hacen falta ${VUS}`);
    return { cajeros, root: login(usuario('root', 0, 'carga-a').username) };
}

export default function (s) {
    const token = s.cajeros[(exec.vu.idInTest - 1) % s.cajeros.length];
    const cuerpo = venta(C.producto, 1);

    const q = http.post(`${BASE_URL}/api/v2/shifts/${C.turno}/sales/quote`, cuerpo, conNombre(token, QUOTE));
    if (q.status >= 500) respuestas5xx.add(1);

    const params = conNombre(token, VENTA, { 'Idempotency-Key': `pos-${exec.scenario.startTime}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}` });
    params.responseCallback = ESPERADOS;
    const v = http.post(`${BASE_URL}/api/v2/shifts/${C.turno}/sales`, cuerpo, params);
    if (v.status === 200) ventasOk.add(1);
    else if (v.status === 409) ventasSinStock.add(1);
    else if (v.status >= 500) { respuestas5xx.add(1); ventasOtro.add(1); } else ventasOtro.add(1);

    check(v, { 'venta 200 o 409 (sin stock), nunca otra cosa': (x) => x.status === 200 || x.status === 409 }, { tipo: 'carrera' });
}

export function teardown(s) {
    // Estado final visto por la API (root ve todo): stock del producto y ventas registradas en el turno
    const st = http.get(`${BASE_URL}/api/v2/stores/${C.local}/stock`, conNombre(s.root, 'GET /api/v2/stores/{id}/stock'));
    const fila = st.status === 200 ? st.json().find((x) => x.productId === C.producto) : null;
    const stockFinal = fila ? fila.quantity : null;
    if (stockFinal !== null && stockFinal < 0) stockNegativo.add(1);

    const vs = http.get(`${BASE_URL}/api/v2/shifts/${C.turno}/sales`, conNombre(s.root, 'GET /api/v2/shifts/{id}/sales'));
    const ventas = vs.status === 200 ? vs.json().length : null;

    console.log(`estado final: stock=${stockFinal} (inicial ${C.stock}), ventas en el turno=${ventas}`);
    check(null, {
        'stock final >= 0': () => stockFinal !== null && stockFinal >= 0,
        'stock final = 0 (se vendio todo, ni una de mas)': () => stockFinal === 0,
        'ventas registradas = stock inicial': () => ventas === C.stock,
    }, { tipo: 'carrera' });
}

export const handleSummary = resumen('pos-concurrencia', (r) => [
    `stock inicial del producto de carrera: ${C.stock}`,
    `pedidos por minuto por cajero (quote + venta, sin pausa: techo, no ritmo real): ${porMinutoPorVu(r, [QUOTE, VENTA], VUS)}`,
]);
