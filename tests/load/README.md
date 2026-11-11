# Pruebas de carga (k6)

Corren contra un **ambiente descartable** dentro del runner de GitHub Actions (o en un docker local). Es `docker-compose.load.yml`, con Postgres, Keycloak y un S3 de prueba propios, en una red sin salida a internet. Nunca corren contra DEV, PROD ni el Keycloak compartido. Los números sirven para comparar corridas, no son la capacidad de PROD.

## Cómo se disparan
- **Etiqueta `k6` en un PR:** corre `load.yml` de la rama del PR con los 6 escenarios. Para repetir, se saca la etiqueta y se vuelve a poner. **Una corrida por cambio**: los minutos de Actions son limitados.
- A mano (`workflow_dispatch`): solo cuando `load.yml` esté en la rama por defecto.
- En local: `tests/load/ambiente/levantar.sh` y después `tests/load/correr.sh humo turnos ...`. Para bajar todo: `levantar.sh bajar`. Necesita varios GB de RAM libres.

## Escenarios
| Archivo | Qué prueba | Falla si |
|---|---|---|
| `humo.js` | 2 usuarios, 1 min, por los flujos principales | errores > 1 %, algún 429 |
| `pos-concurrencia.js` | 5 cajeros sin pausa sobre un producto con stock 50 | ventas OK ≠ 50, stock < 0, 5xx |
| `idempotencia.js` | la misma `Idempotency-Key` enviada 6 veces | más de una venta por clave |
| `turnos.js` | 2 aperturas de turno simultáneas en el mismo local, 100 rondas | 2 turnos abiertos, ronda sin apertura OK, 5xx |
| `ajuste-vs-venta.js` | mermas y reposiciones del encargado mientras venden 3 cajas | stock < 0, stock ≠ suma de movimientos, `DESCUADRE` cliente/servidor |
| `informes.js` | rampa 1→5 VUs con informes de ventas y productos de 30, 90 y 365 días (PDF y XLSX), 3 empresas | 5xx, 365 días p95 ≥ 10 s, 30 días p95 ≥ 3 s |
| `listados.js` | rampa 1→6 VUs: `/api/v3/operations` de 30 y 365 días (página 0 y 50), `size=5000`, resumen, ventas de 90 días y movimientos | 5xx, p95 ≥ 1 s |
| `carga-multi-tenant.js` | rampa 10→50 VUs en 3 empresas: lecturas, ventas e intentos de leer otra empresa | **una sola fuga entre empresas**, 5xx, p95 ≥ 1 s |
| `rate-limit.js` | **valores de PROD** (300/min por usuario, 100/min por IP sin token, techo de 1.000/min por IP), todo desde la misma IP | algún 429 a cajas o a un usuario a 250/min; el usuario pasado, los anónimos o el techo no se cortan; 429 sin `Retry-After` |

- `rate-limit.js` es el único escenario con los límites reales: `correr.sh` reinicia el backend descartable con esos valores antes y vuelve a los altos después.
- El ritmo de caja (5 cajas por local, 2 ventas/min) está **a confirmar**.
- Un umbral que falla es un **hallazgo**: va como propuesta a la mejora continua (vault), no se "arregla" bajando el umbral.

## Resultados
El artifact `k6-<n>` lleva **solo** `k6-summary.json` e `informe.md` por escenario. Incluye p95/p99 por endpoint, docker stats y, cada 5 s, el pool Hikari, el heap y la CPU del backend. Antes de subirlo, el workflow verifica que no tenga tokens ni claves. El repo es público: nunca agregar logs, headers ni cuerpos de respuesta al artifact.
