package balance.security.http;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorre por reflexión los {@code @RequestMapping} (y @GetMapping, @PostMapping, etc.) de
 * todos los {@code @RestController} del paquete {@code balance} y exige que cada endpoint:
 * <ul>
 *   <li>tenga al menos un caso en el test entre empresas ({@link CrossTenantCatalog}) o esté
 *       excluido con motivo, y</li>
 *   <li>tenga al menos una fila en la matriz de permisos ({@link PermissionMatrix}) o esté
 *       excluido con motivo.</li>
 * </ul>
 * No necesita Docker: corre con los unitarios ({@code mvn test}), así un endpoint nuevo sin
 * cobertura falla rápido aunque los IT no se corran localmente.
 *
 * <p>Fuera de este recorrido quedan, por no ser controllers del paquete: actuator
 * (/actuator/health e /info, públicos), springdoc (/swagger-ui, /api-docs) y el /error de Spring.
 */
class EndpointCoverageTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** "MÉTODO /ruta/{var}" de cada handler de los controllers de negocio. */
    static Set<String> endpointsDeLosControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> endpoints = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("balance")) {
            Class<?> controller = ClassUtils.resolveClassName(bd.getBeanClassName(), EndpointCoverageTest.class.getClassLoader());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefijos = base != null && base.path().length > 0 ? base.path() : new String[]{""};
            for (Method m : controller.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] rutas = rm.path().length > 0 ? rm.path() : new String[]{""};
                RequestMethod[] metodos = rm.method().length > 0 ? rm.method() : RequestMethod.values();
                for (String prefijo : prefijos) {
                    for (String ruta : rutas) {
                        for (RequestMethod metodo : metodos) {
                            endpoints.add(metodo.name() + " " + prefijo + ruta);
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    /** Normaliza los nombres de variables: "/a/{id}" y "/a/{storeId}" son el mismo endpoint. */
    static String normalizar(String endpoint) {
        return endpoint.replaceAll("\\{[^}]+}", "{}");
    }

    /** El endpoint (patrón) que Spring elegiría para un pedido concreto: el más específico que matchea. */
    static Optional<String> endpointQueAtiende(String metodo, String rutaConQuery, Set<String> endpoints) {
        String ruta = rutaConQuery.contains("?") ? rutaConQuery.substring(0, rutaConQuery.indexOf('?')) : rutaConQuery;
        Comparator<String> masEspecifico = MATCHER.getPatternComparator(ruta);
        return endpoints.stream()
                .filter(e -> e.startsWith(metodo + " "))
                .filter(e -> MATCHER.match(e.substring(metodo.length() + 1), ruta))
                .min((a, b) -> masEspecifico.compare(a.substring(metodo.length() + 1), b.substring(metodo.length() + 1)));
    }

    @Test
    void elRecorridoEncuentraLosControllers() {
        // Si el escaneo dejara de encontrar controllers, los otros tests pasarían en vacío.
        assertThat(endpointsDeLosControllers())
                .contains("GET /api/v2/shifts/{shiftId}/sales", "DELETE /api/v3/deposits/{id}", "PUT /transactions/{id}")
                .hasSizeGreaterThan(100);
    }

    @Test
    void todoEndpointTieneCasoEntreEmpresas_oExclusionJustificada() {
        Set<String> reales = endpointsDeLosControllers();
        Set<String> cubiertos = new TreeSet<>();
        CrossTenantCatalog.CASOS.forEach(c -> cubiertos.add(normalizar(c.endpoint())));
        CrossTenantCatalog.EXCLUIDOS.keySet().forEach(e -> cubiertos.add(normalizar(e)));

        List<String> sinCobertura = new ArrayList<>();
        for (String e : reales) {
            if (!cubiertos.contains(normalizar(e))) sinCobertura.add(e);
        }
        assertThat(sinCobertura)
                .as("Endpoints sin caso en CrossTenantCatalog: agregá un caso (o una exclusión con motivo)")
                .isEmpty();

        Set<String> realesNormalizados = new TreeSet<>();
        reales.forEach(e -> realesNormalizados.add(normalizar(e)));
        assertThat(cubiertos)
                .as("Casos o exclusiones de endpoints que ya no existen en los controllers")
                .allMatch(realesNormalizados::contains);
        assertThat(CrossTenantCatalog.EXCLUIDOS.values()).allMatch(motivo -> !motivo.isBlank());
    }

    @Test
    void cadaCasoEntreEmpresas_pegaAlEndpointQueDice() {
        Set<String> reales = endpointsDeLosControllers();
        List<String> errores = new ArrayList<>();
        for (CrossTenantCatalog.Caso c : CrossTenantCatalog.CASOS) {
            Optional<String> real = endpointQueAtiende(c.metodo(), c.ruta(), reales);
            if (real.isEmpty() || !normalizar(real.get()).equals(normalizar(c.endpoint()))) {
                errores.add(c + " declara " + c.endpoint() + " pero lo atiende " + real.orElse("ninguno"));
            }
        }
        assertThat(errores).isEmpty();
    }

    @Test
    void todoEndpointTieneFilaEnLaMatriz_oExclusionJustificada() {
        Set<String> reales = endpointsDeLosControllers();
        Set<String> cubiertos = new TreeSet<>();
        List<String> filasHuerfanas = new ArrayList<>();
        for (PermissionMatrix.Fila f : PermissionMatrix.FILAS) {
            Optional<String> e = endpointQueAtiende(f.metodo(), f.ruta(), reales);
            if (e.isPresent()) {
                cubiertos.add(e.get());
            } else {
                filasHuerfanas.add(f.metodo() + " " + f.ruta());
            }
        }
        assertThat(filasHuerfanas).as("Filas de la matriz que no pegan a ningún endpoint").isEmpty();

        List<String> sinCobertura = new ArrayList<>();
        for (String e : reales) {
            if (!cubiertos.contains(e) && !PermissionMatrix.EXCLUIDOS.containsKey(e)) sinCobertura.add(e);
        }
        assertThat(sinCobertura)
                .as("Endpoints sin fila en PermissionMatrix: agregá una fila (o una exclusión con motivo)")
                .isEmpty();
        assertThat(reales).containsAll(PermissionMatrix.EXCLUIDOS.keySet());
    }
}
