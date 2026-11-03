package balance.security.http;

import balance.security.http.PermissionMatrix.Celda;
import balance.security.http.PermissionMatrix.Rol;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorre {@link PermissionMatrix}: cada fila × cada rol es un caso. "La regla #1 de Jorge
 * (permisos por rol) convertida en test": si un cambio abre o cierra un permiso sin querer,
 * falla la celda exacta (rol + endpoint).
 */
class PermissionMatrixIT extends HttpSecurityTestBase {

    static Stream<Celda> celdas() {
        return PermissionMatrix.FILAS.stream()
                .flatMap(f -> Arrays.stream(Rol.values()).map(r -> new Celda(r, f)))
                .filter(c -> !c.esperado().equals("-"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("celdas")
    void cadaRolRecibeLoQueDiceLaMatriz(Celda celda) {
        Respuesta r = llamar(celda.fila().metodo(), celda.fila().ruta(), celda.fila().body(),
                celda.rol().usuario.token());

        assertThat(r.status()).as("nunca 5xx. %s -> %s", celda, r.resumen()).isLessThan(500);
        if (celda.esperado().equals("OK")) {
            assertThat(r.es2xx()).as("esperaba 2xx. %s -> %s", celda, r.resumen()).isTrue();
        } else {
            assertThat(r.status()).as("esperaba 403. %s -> %s", celda, r.resumen()).isEqualTo(403);
        }
    }
}
