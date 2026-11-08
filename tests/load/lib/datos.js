// Mejora continua v4 (k6): datos sinteticos generados por levantar.sh (usuarios @belopia.test e ids de la semilla).
// Ninguno de estos archivos tiene claves: la clave llega por la variable LOAD_USER_PASSWORD.
import { SharedArray } from 'k6/data';

const USUARIOS = new SharedArray('usuarios', () => JSON.parse(open('../.generado/usuarios.json')));
const DATOS = JSON.parse(open('../.generado/datos.json'));

/** Usuarios de un papel (root, encargado, contador, cajero), opcionalmente de una empresa (carga-a/b/c). */
export function usuarios(papel, empresa) {
    return USUARIOS.filter((u) => u.papel === papel && (!empresa || u.empresa === empresa));
}

/** Un usuario del papel, elegido en ronda por indice. */
export function usuario(papel, i = 0, empresa) {
    const lista = usuarios(papel, empresa);
    if (lista.length === 0) throw new Error(`No hay usuarios sinteticos con papel ${papel}`);
    return lista[i % lista.length];
}

/** Empresa de la semilla: { tenant, slug, locales: [{ id, turno, productos: [...] }] }. */
export function empresa(slug) {
    const e = DATOS.empresas.find((x) => x.slug === slug);
    if (!e) throw new Error(`No existe la empresa sintetica ${slug}`);
    return e;
}

/** Local 91: { local, producto, turno, stock } (stock limitado para la carrera). */
export function carrera() {
    return DATOS.carrera;
}

/** Local 92: { local, producto, turno } (stock alto para las reintentos con la misma clave). */
export function idempotencia() {
    return DATOS.idempotencia;
}

/** Cuerpo de una venta en efectivo (el usuario lo pisa el backend con el del token, pero es obligatorio). */
export function venta(productId, cantidad = 1) {
    return JSON.stringify({ username: 'k6', items: [{ productId, quantity: cantidad }], paymentMethod: 'CASH' });
}
