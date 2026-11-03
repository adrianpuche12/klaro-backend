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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;
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
    @Mock private S3Presigner presigner;
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
    void upload_returnsKeyWithCurrentTenantSlug_andDetectedContentType() {
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));

        String key = r2StorageService.upload("contenido".getBytes(), ComprobanteFileType.PNG, "comprobantes");

        // Clave del objeto, no URL pública: el bucket pasa a ser privado.
        assertThat(key).startsWith("dev/acme/comprobantes/").endsWith(".png");
        assertThat(key).doesNotStartWith("http");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void upload_fallsBackToTenantIdSlug_whenTenantRowMissing() {
        // Caso root (tenant_id=0) u otro tenantId sin fila propia en `tenants`.
        TenantContext.setTenantId(0L);
        when(tenantRepository.findById(0L)).thenReturn(Optional.empty());

        String key = r2StorageService.upload("x".getBytes(), ComprobanteFileType.JPEG, "comprobantes");

        assertThat(key).startsWith("dev/tenant_0/comprobantes/").endsWith(".jpg");
    }

    @Test
    void upload_throwsSecurityException_whenNoTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> r2StorageService.upload("x".getBytes(), ComprobanteFileType.PDF, "comprobantes"))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(s3);
    }

    // ── resolveTenantKey() ──────────────────────────────────────────────────

    @Test
    void resolveTenantKey_acceptsOwnKey() {
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));

        assertThat(r2StorageService.resolveTenantKey("dev/acme/comprobantes/1_a.jpg"))
                .isEqualTo("dev/acme/comprobantes/1_a.jpg");
    }

    @Test
    void resolveTenantKey_extractsKeyFromLegacyPublicUrl() {
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));

        assertThat(r2StorageService.resolveTenantKey("https://pub-test.r2.dev/dev/acme/comprobantes/1_a.jpg"))
                .isEqualTo("dev/acme/comprobantes/1_a.jpg");
        assertThat(r2StorageService.legacyPublicUrl("dev/acme/comprobantes/1_a.jpg"))
                .isEqualTo("https://pub-test.r2.dev/dev/acme/comprobantes/1_a.jpg");
    }

    @Test
    void resolveTenantKey_rejectsKeyOfAnotherTenant() {
        TenantContext.setTenantId(TENANT_B_ID);
        when(tenantRepository.findById(TENANT_B_ID)).thenReturn(Optional.of(tenant(TENANT_B_ID, "otraempresa")));

        assertThatThrownBy(() -> r2StorageService.resolveTenantKey("dev/acme/comprobantes/1_a.jpg"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> r2StorageService.resolveTenantKey(
                "https://pub-test.r2.dev/dev/acme/comprobantes/1_a.jpg"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolveTenantKey_rejectsSlugThatOnlySharesPrefix() {
        // "acme" no puede leer "acme2": el prefijo se compara con la barra final.
        TenantContext.setTenantId(TENANT_A_ID);
        when(tenantRepository.findById(TENANT_A_ID)).thenReturn(Optional.of(tenant(TENANT_A_ID, "acme")));

        assertThatThrownBy(() -> r2StorageService.resolveTenantKey("dev/acme2/comprobantes/1_a.jpg"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolveTenantKey_urlOutsideOwnBucket_is404() {
        TenantContext.setTenantId(TENANT_A_ID);

        assertThatThrownBy(() -> r2StorageService.resolveTenantKey("https://evil.example.com/dev/acme/x.jpg"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void resolveTenantKey_pathTraversal_is404() {
        TenantContext.setTenantId(TENANT_A_ID);

        assertThatThrownBy(() -> r2StorageService.resolveTenantKey("dev/acme/../otraempresa/x.jpg"))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── presignedGetUrl() ───────────────────────────────────────────────────

    @Test
    void presignedGetUrl_expiresInFiveMinutes() throws Exception {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://r2.example.com/belopia-comprobantes/dev/acme/x.jpg?X-Amz-Signature=abc"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = r2StorageService.presignedGetUrl("dev/acme/comprobantes/x.jpg");

        assertThat(url).contains("X-Amz-Signature");
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(captor.getValue().getObjectRequest().bucket()).isEqualTo("belopia-comprobantes");
        assertThat(captor.getValue().getObjectRequest().key()).isEqualTo("dev/acme/comprobantes/x.jpg");
        assertThat(captor.getValue().getObjectRequest().responseContentDisposition()).isNull();
    }

    @Test
    void presignedGetUrl_legacyNonImageObject_isForcedAsDownload() throws Exception {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://r2.example.com/x"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        r2StorageService.presignedGetUrl("dev/acme/comprobantes/viejo.svg");

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().getObjectRequest().responseContentDisposition()).isEqualTo("attachment");
        assertThat(captor.getValue().getObjectRequest().responseContentType()).isEqualTo("application/octet-stream");
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
