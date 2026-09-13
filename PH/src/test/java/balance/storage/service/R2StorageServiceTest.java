package balance.storage.service;

import balance.tenant.context.TenantContext;
import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SPRINT-13 — aislamiento por tenant en R2StorageService.
 * Mismo patrón que balance.security.TenantIsolationTest, aplicado al storage
 * de archivos: un tenant nunca puede eliminar (ni construir un path que
 * pise) el archivo de otro.
 */
@ExtendWith(MockitoExtension.class)
class R2StorageServiceTest {

    private static final Long TENANT_A_ID = 1L;
    private static final Long TENANT_B_ID = 2L;

    @InjectMocks private R2StorageService r2StorageService;
    @Mock private S3Client s3;
    @Mock private TenantRepository tenantRepository;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(r2StorageService, "bucket", "belopia-comprobantes");
        ReflectionTestUtils.setField(r2StorageService, "publicUrl", "https://pub-test.r2.dev");
        ReflectionTestUtils.setField(r2StorageService, "folderPrefix", "dev");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private Tenant tenant(Long id, String slug) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setSlug(slug);
        return t;
    }

    // ── upload() ────────────────────────────────────────────────────────────

    @Test
    void upload_pathIncludesCurrentTenantSlug() {
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", "contenido".getBytes());

        String url = r2StorageService.upload(file, "comprobantes");

        assertThat(url).startsWith("https://pub-test.r2.dev/dev/acme/comprobantes/");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).startsWith("dev/acme/comprobantes/");
    }

    @Test
    void upload_fallsBackToTenantIdSlug_whenTenantRowMissing() {
        // Caso root (tenant_id=0) u otro tenantId sin fila propia en `tenants`.
        TenantContext.setTenantId(0L);
        when(tenantRepository.findById(0L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", "x".getBytes());

        String url = r2StorageService.upload(file, "comprobantes");

        assertThat(url).startsWith("https://pub-test.r2.dev/dev/tenant_0/comprobantes/");
    }

    @Test
    void upload_throwsSecurityException_whenNoTenantContext() {
        TenantContext.clear();
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", "x".getBytes());

        assertThatThrownBy(() -> r2StorageService.upload(file, "comprobantes"))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(s3);
    }

    // ── delete() ────────────────────────────────────────────────────────────

    @Test
    void delete_tenantCanDeleteItsOwnFile() {
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));

        r2StorageService.delete("https://pub-test.r2.dev/dev/acme/comprobantes/1700000000000_abcd1234.jpg");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("dev/acme/comprobantes/1700000000000_abcd1234.jpg");
    }

    @Test
    void delete_tenantBCannotDeleteTenantAFile() {
        TenantContext.setTenantId(TENANT_B_ID);
        when(tenantRepository.findById(TENANT_B_ID)).thenReturn(Optional.of(tenant(TENANT_B_ID, "otraempresa")));

        assertThatThrownBy(() -> r2StorageService.delete(
                "https://pub-test.r2.dev/dev/acme/comprobantes/1700000000000_abcd1234.jpg"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("otro tenant");

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void delete_throwsSecurityException_whenNoTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> r2StorageService.delete(
                "https://pub-test.r2.dev/dev/acme/comprobantes/1700000000000_abcd1234.jpg"))
                .isInstanceOf(SecurityException.class);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void delete_ignoresUrlOutsideOwnBucket_withoutTouchingTenantContext() {
        TenantContext.setTenantId(TENANT_A_ID);

        r2StorageService.delete("https://otro-dominio-cualquiera.com/algo.jpg");

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void delete_ignoresNullUrl() {
        TenantContext.setTenantId(TENANT_A_ID);

        r2StorageService.delete(null);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verifyNoInteractions(tenantRepository);
    }
}
