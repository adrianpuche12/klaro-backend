package balance.tenant.service;

import balance.common.enums.AppUserStatus;
import balance.tenant.context.TenantContext;
import balance.tenant.dto.TenantOnboardingRequestDTO;
import balance.tenant.dto.TenantResponseDTO;
import balance.tenant.model.Tenant;
import balance.tenant.model.TenantConfig;
import balance.tenant.repository.TenantConfigRepository;
import balance.tenant.repository.TenantRepository;
import balance.users.model.AppUser;
import balance.users.repository.AppUserRepository;
import balance.users.service.KeycloakAdminService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * Alta completa de una empresa nueva, en un solo paso (SPRINT-16). Solo la usa el administrador de plataforma.
 *
 * <ol>
 *   <li>Como plataforma (tenant 0): crea la fila de {@code tenants} y su {@code tenant_config}
 *       (las únicas tablas que la política RLS le habilita al tenant 0, V12).</li>
 *   <li>Crea el usuario dueño en Keycloak con rol "root" y el tenant nuevo.</li>
 *   <li>Como el tenant nuevo: crea la fila de {@code app_users} del dueño (RLS solo acepta filas del tenant de la sesión).</li>
 * </ol>
 *
 * Cada paso corre en su propia transacción ({@code REQUIRES_NEW}) y fija el tenant EXPLÍCITAMENTE sobre la conexión
 * que esté usando ({@link #inTenant}). No alcanza con el checkout del pool: con open-in-view (activo en el servidor)
 * el request reusa la misma conexión en todos los pasos — bug encontrado en DEV el 01-Nov-2026.
 * Si algo falla después del paso 1, se deshace todo (usuario de Keycloak incluido) y se relanza el error.
 */
@Service
public class TenantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardingService.class);
    static final String ROOT_KEYCLOAK_ROLE = "root";

    private final TenantRepository tenantRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final AppUserRepository appUserRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final TransactionTemplate newTx;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantOnboardingService(TenantRepository tenantRepository,
                                   TenantConfigRepository tenantConfigRepository,
                                   AppUserRepository appUserRepository,
                                   KeycloakAdminService keycloakAdminService,
                                   PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.tenantConfigRepository = tenantConfigRepository;
        this.appUserRepository = appUserRepository;
        this.keycloakAdminService = keycloakAdminService;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record OnboardingResult(TenantResponseDTO tenant, String rootUsername) {}

    public OnboardingResult onboard(TenantOnboardingRequestDTO dto) {
        String slug = dto.getSlug().trim().toLowerCase();
        String rootUsername = dto.getRootUsername().trim().toLowerCase();
        String timezone = dto.getTimezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Zona horaria invalida: " + timezone);
        }

        // 1. Empresa + configuración, como plataforma.
        Tenant tenant = inTenant(TenantContext.PLATFORM_TENANT_ID, () -> {
            if (tenantRepository.existsBySlug(slug)) {
                throw new IllegalArgumentException("Ya existe una empresa con el slug: " + slug);
            }
            Tenant t = new Tenant();
            t.setSlug(slug);
            t.setName(dto.getName().trim());
            t.setPlan(dto.getPlan() != null ? dto.getPlan() : "starter");
            t.setActive(true);
            Tenant saved = tenantRepository.save(t);

            TenantConfig config = new TenantConfig();
            config.setTenantId(saved.getId());
            config.setCompanyName(saved.getName());
            config.setCurrency(dto.getCurrency().trim().toUpperCase());
            config.setTimezone(timezone);
            config.setAddress(blankToNull(dto.getAddress()));
            config.setPhone(blankToNull(dto.getPhone()));
            tenantConfigRepository.save(config);
            return saved;
        });

        String keycloakId = null;
        try {
            // 2. Usuario dueño en Keycloak.
            keycloakId = keycloakAdminService.createUser(
                    rootUsername, dto.getRootFullName(), dto.getRootPassword(), ROOT_KEYCLOAK_ROLE, tenant.getId());

            // 3. Fila del dueño en app_users, como el tenant nuevo.
            final String kcId = keycloakId;
            inTenant(tenant.getId(), () -> {
                AppUser root = new AppUser();
                root.setKeycloakId(kcId);
                root.setUsername(rootUsername);
                root.setFullName(dto.getRootFullName().trim());
                root.setStatus(AppUserStatus.ACTIVE);
                root.setTenantId(tenant.getId());
                return appUserRepository.save(root);
            });

            log.info("Empresa creada: id={} slug={} dueño={}", tenant.getId(), slug, rootUsername);
            return new OnboardingResult(TenantResponseDTO.from(tenant), rootUsername);

        } catch (RuntimeException e) {
            log.warn("Alta de empresa {} fallida, deshaciendo: {}", slug, e.getMessage());
            rollback(tenant.getId(), keycloakId);
            throw e;
        }
    }

    private void rollback(Long tenantId, String keycloakId) {
        if (keycloakId != null) {
            try {
                keycloakAdminService.deleteUser(keycloakId);
            } catch (RuntimeException e) {
                log.error("No se pudo borrar el usuario de Keycloak {} del alta fallida de la empresa {}", keycloakId, tenantId, e);
            }
        }
        try {
            inTenant(TenantContext.PLATFORM_TENANT_ID, () -> {
                tenantConfigRepository.deleteById(tenantId);
                tenantRepository.deleteById(tenantId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("No se pudo borrar la empresa {} del alta fallida", tenantId, e);
        }
    }

    /**
     * Ejecuta {@code action} en una transacción nueva con {@code tenantId}: lo pone en TenantContext y lo fija sobre
     * la conexión de esa transacción. Al terminar deja la conexión con el tenant que tenía el request.
     */
    private <T> T inTenant(long tenantId, Supplier<T> action) {
        Long previous = TenantContext.getTenantId();
        try {
            return TenantContext.runAs(tenantId, () -> newTx.execute(status -> {
                setConnectionTenant(tenantId);
                return action.get();
            }));
        } finally {
            if (previous != null && previous != tenantId) {
                TenantContext.runAs(previous, () -> newTx.execute(status -> {
                    setConnectionTenant(previous);
                    return null;
                }));
            }
        }
    }

    private void setConnectionTenant(long tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tenant, false)")
                .setParameter("tenant", Long.toString(tenantId))
                .getSingleResult();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
