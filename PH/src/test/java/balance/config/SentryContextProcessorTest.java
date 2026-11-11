package balance.config;

import balance.tenant.context.TenantContext;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentryContextProcessorTest {

    private final SentryContextProcessor processor = new SentryContextProcessor();

    @AfterEach
    void limpiar() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return req;
    }

    @Test
    void etiquetaEmpresaYLocalDeLaRuta() {
        TenantContext.setTenantId(4L);
        MockHttpServletRequest req = request("/api/v2/stores/3/stock");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("storeId", "3"));

        SentryEvent e = processor.process(new SentryEvent(), new Hint());

        assertThat(e.getTag("tenant_id")).isEqualTo("4");
        assertThat(e.getTag("store_id")).isEqualTo("3");
    }

    @Test
    void localDesdeElParametro() {
        TenantContext.setTenantId(4L);
        request("/api/v2/reports/sales").setParameter("storeId", "7");

        assertThat(processor.process(new SentryEvent(), new Hint()).getTag("store_id")).isEqualTo("7");
    }

    @Test
    void storeIdQueNoEsNumeroNoSeEtiqueta() {
        request("/x").setParameter("storeId", "<script>");
        assertThat(processor.process(new SentryEvent(), new Hint()).getTag("store_id")).isNull();
    }

    @Test
    void sinRequestNiEmpresaNoRompe() {
        SentryEvent e = processor.process(new SentryEvent(), new Hint());
        assertThat(e.getTag("tenant_id")).isNull();
        assertThat(e.getTag("store_id")).isNull();
    }

    @Test
    void noAgregaDatosPersonales() {
        TenantContext.setTenantId(4L);
        SentryEvent e = processor.process(new SentryEvent(), new Hint());
        User u = e.getUser();
        assertThat(u).isNull();
        assertThat(e.getTags()).containsOnlyKeys("tenant_id");
    }
}
