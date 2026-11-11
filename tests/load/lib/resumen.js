// Mejora continua v4 (k6): lo UNICO que sale del runner como artifact es k6-summary.json e informe.md, generados aca.
// Sin headers, tokens, cuerpos de respuesta ni logs (ver MEJORA CONTINUA/05-Pruebas de carga k6, "Seguridad de los
// resultados"; el repo es publico y ya hubo una fuga por artifacts de Playwright).
const DIR = __ENV.RESULTADOS || 'resultados';

const SOLO_PARA_MEDIR = ['max>=0', 'count>=0'];

/** Contadores de negocio que, si existen en el escenario, van a la seccion Totales del informe. */
const CONTADORES = ['ventas_ok', 'ventas_sin_stock', 'ventas_otro_estado', 'ventas_duplicadas', 'claves_con_venta',
    'respuestas_5xx', 'stock_negativo',
    // PR 4: turnos.js, ajuste-vs-venta.js y rate-limit.js
    'turnos_abiertos_ok', 'turnos_dobles', 'aperturas_perdidas', 'aperturas_400',
    'entradas_ok', 'mermas_ok', 'mermas_sin_stock', 'respuestas_otro_estado', 'descuadres', 'stock_final', 'ventas_registradas',
    'ventas_caja_ok', 'aceptados_pasado', 'aceptados_anonimos', 'aceptados_techo', 'respuestas_429_sin_retry_after',
    // PR 4 parte 2: carga-multi-tenant.js
    'fugas_entre_empresas'];

/**
 * Umbrales por endpoint para options.thresholds. k6 solo calcula la metrica de un tag (name) si hay un umbral sobre
 * ella: los que no tienen umbral propio reciben uno que siempre pasa (max>=0) y no aparecen en la tabla de umbrales.
 * reales: { 'POST /api/v2/shifts/{id}/sales': ['p(95)<500'] }.
 */
export function porEndpoint(nombres, reales = {}) {
    const t = {};
    for (const n of nombres) {
        t[`http_req_duration{name:${n}}`] = reales[n] || ['max>=0'];
        t[`http_reqs{name:${n}}`] = ['count>=0'];
    }
    return t;
}

/** Umbral sobre las respuestas 429 (rate limit) y su conteo para el informe. */
export const SIN_429 = { 'http_reqs{status:429}': ['count==0'] };

function ms(v) {
    return v === undefined || v === null ? '-' : `${Math.round(v)} ms`;
}

function pct(v) {
    return v === undefined || v === null ? '-' : `${(v * 100).toFixed(2)} %`;
}

/** Arma el resumen limpio: metricas y umbrales, nada de lo que viaja en los pedidos. */
function limpio(escenario, data) {
    const metricas = {};
    for (const [nombre, m] of Object.entries(data.metrics)) {
        metricas[nombre] = {
            tipo: m.type,
            valores: m.values,
            umbrales: m.thresholds ? Object.fromEntries(Object.entries(m.thresholds).map(([k, t]) => [k, t.ok])) : undefined,
        };
    }
    const checks = [];
    const recorrer = (g) => {
        for (const c of g.checks || []) checks.push({ nombre: c.name, pasaron: c.passes, fallaron: c.fails });
        for (const sg of g.groups || []) recorrer(sg);
    };
    recorrer(data.root_group);
    return {
        escenario,
        commit: __ENV.COMMIT || 'local',
        fecha: new Date().toISOString(),
        duracion_ms: data.state ? data.state.testRunDurationMs : undefined,
        metricas,
        checks,
    };
}

function informe(r, extras) {
    const lineas = [];
    lineas.push(`# k6 - ${r.escenario}`);
    lineas.push('');
    lineas.push(`- Commit probado: \`${r.commit}\``);
    lineas.push(`- Fecha: ${r.fecha}`);
    lineas.push(`- Duracion: ${r.duracion_ms ? Math.round(r.duracion_ms / 1000) + ' s' : '-'}`);
    lineas.push('- Ambiente: descartable en el runner (los numeros sirven para comparar corridas, no son la capacidad de PROD)');
    lineas.push('');
    lineas.push('## Umbrales (tecnicos, a confirmar con la linea de base)');
    lineas.push('| Metrica | Umbral | Resultado |');
    lineas.push('|---|---|---|');
    let algunoFallo = false;
    for (const [nombre, m] of Object.entries(r.metricas)) {
        for (const [u, ok] of Object.entries(m.umbrales || {})) {
            if (SOLO_PARA_MEDIR.includes(u)) continue;
            if (!ok) algunoFallo = true;
            lineas.push(`| \`${nombre}\` | \`${u}\` | ${ok ? 'PASA' : '**FALLA**'} |`);
        }
    }
    lineas.push('');
    lineas.push(`Resultado general: ${algunoFallo ? '**hay umbrales que fallan (hallazgo)**' : 'todos los umbrales pasan'}`);
    lineas.push('');
    lineas.push('## Checks');
    lineas.push('| Check | Pasaron | Fallaron |');
    lineas.push('|---|---|---|');
    for (const c of r.checks) lineas.push(`| ${c.nombre} | ${c.pasaron} | ${c.fallaron ? '**' + c.fallaron + '**' : 0} |`);
    lineas.push('');
    lineas.push('## Tiempos por endpoint');
    lineas.push('| Endpoint | Pedidos | p50 | p95 | p99 | max |');
    lineas.push('|---|---|---|---|---|---|');
    for (const [nombre, m] of Object.entries(r.metricas)) {
        const g = /^http_req_duration\{name:(.+)\}$/.exec(nombre);
        if (!g) continue;
        const v = m.valores;
        const pedidos = r.metricas[`http_reqs{name:${g[1]}}`];
        lineas.push(`| ${g[1]} | ${pedidos ? pedidos.valores.count : '-'} | ${ms(v.med)} | ${ms(v['p(95)'])} | ${ms(v['p(99)'])} | ${ms(v.max)} |`);
    }
    const tot = r.metricas.http_req_duration;
    const err = r.metricas.http_req_failed;
    const reqs = r.metricas.http_reqs;
    const r429 = r.metricas['http_reqs{status:429}'];
    lineas.push('');
    lineas.push('## Totales');
    lineas.push(`- Pedidos: ${reqs ? reqs.valores.count : '-'} (${reqs ? reqs.valores.rate.toFixed(1) : '-'} por segundo)`);
    lineas.push(`- Errores HTTP (http_req_failed): ${err ? pct(err.valores.rate) : '-'}`);
    lineas.push(`- Respuestas 429 (rate limit): ${r429 ? r429.valores.count : 0}`);
    lineas.push(`- Duracion de pedidos: p95 ${tot ? ms(tot.valores['p(95)']) : '-'}, p99 ${tot ? ms(tot.valores['p(99)']) : '-'}`);
    for (const c of CONTADORES) {
        if (r.metricas[c]) lineas.push(`- ${c}: ${r.metricas[c].valores.count}`);
    }
    for (const l of extras ? extras(r) : []) lineas.push(`- ${l}`);
    lineas.push('');
    return lineas.join('\n');
}

/**
 * handleSummary comun: escribe solo los dos archivos permitidos y un texto corto en la consola.
 * extras(r): lineas propias del escenario para Totales (por ejemplo, pedidos por minuto por cajero).
 */
export function resumen(escenario, extras) {
    return (data) => {
        const r = limpio(escenario, data);
        const md = informe(r, extras);
        return {
            [`${DIR}/${escenario}/k6-summary.json`]: JSON.stringify(r, null, 1),
            [`${DIR}/${escenario}/informe.md`]: md,
            stdout: md + '\n',
        };
    };
}

/** Pedidos por minuto por VU de un conjunto de endpoints (para calibrar el rate limit por usuario). */
export function porMinutoPorVu(r, nombres, vus) {
    const total = nombres.reduce((s, n) => s + ((r.metricas[`http_reqs{name:${n}}`] || { valores: { count: 0 } }).valores.count), 0);
    const minutos = (r.duracion_ms || 0) / 60000;
    return minutos > 0 ? (total / vus / minutos).toFixed(1) : '-';
}
