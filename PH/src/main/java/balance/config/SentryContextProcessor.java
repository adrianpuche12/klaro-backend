package balance.config;

import balance.tenant.context.TenantContext;
import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Etiqueta cada evento de Sentry con la empresa ({@code tenant_id}) y el local ({@code store_id})
 * del request, para saber si un error afecta a un cliente o a todos.
 *
 * <p>Solo ids, nunca nombre, email ni usuario: con el id alcanza para soporte y no saca datos
 * personales del servidor. El starter de Sentry registra solo los beans {@link EventProcessor}.
 * La versión ({@code release}) sale de la variable SENTRY_RELEASE que pone el Dockerfile.
 */
@Component
public class SentryContextProcessor implements EventProcessor {

    static final String TAG_TENANT = "tenant_id";
    static final String TAG_STORE = "store_id";

    @Override
    public SentryEvent process(SentryEvent event, Hint hint) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            event.setTag(TAG_TENANT, tenantId.toString());
        }
        String storeId = storeIdDelRequest();
        if (storeId != null) {
            event.setTag(TAG_STORE, storeId);
        }
        return event;
    }

    /** {storeId} de la ruta (/stores/{storeId}/...) o ?storeId=; solo si es un número. */
    @SuppressWarnings("unchecked")
    static String storeIdDelRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return null;
        }
        HttpServletRequest request = sra.getRequest();
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String valor = vars instanceof Map<?, ?> m ? (String) ((Map<String, String>) m).get("storeId") : null;
        if (valor == null) {
            valor = request.getParameter("storeId");
        }
        return valor != null && valor.matches("\\d{1,19}") ? valor : null;
    }
}
