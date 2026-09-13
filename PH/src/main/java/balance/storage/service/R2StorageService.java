package balance.storage.service;

import balance.tenant.context.TenantSecurityUtils;
import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Service
public class R2StorageService {

    @Value("${r2.access-key}")      private String accessKey;
    @Value("${r2.secret-key}")      private String secretKey;
    @Value("${r2.endpoint}")        private String endpoint;
    @Value("${r2.bucket}")          private String bucket;
    @Value("${r2.public-url}")      private String publicUrl;
    @Value("${r2.folder-prefix:prod}") private String folderPrefix;

    @Autowired private TenantRepository tenantRepository;

    private S3Client s3;

    @PostConstruct
    private void init() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * Sube un archivo a R2 y devuelve su URL pública.
     * El path incluye el tenant-slug del contexto actual -- aislamiento por
     * tenant (SPRINT-13, ver "03. Arquitectura Multi-Tenancy.md").
     * @param file     archivo recibido por multipart
     * @param folder   carpeta dentro del bucket, relativa al tenant (ej: "comprobantes")
     * @return URL pública accesible desde el navegador
     */
    public String upload(MultipartFile file, String folder) {
        String tenantSlug = resolveTenantSlug(TenantSecurityUtils.requireTenantId());
        try {
            String ext      = getExtension(file.getOriginalFilename());
            String fileName = folderPrefix + "/" + tenantSlug + "/" + folder + "/" + Instant.now().toEpochMilli()
                              + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;

            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(fileName)
                    .contentType(file.getContentType())
                    .build();

            s3.putObject(req, RequestBody.fromBytes(file.getBytes()));

            return publicUrl + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error al subir imagen a R2: " + e.getMessage(), e);
        }
    }

    /**
     * Elimina un archivo de R2 a partir de su URL pública.
     * Valida que el path pertenezca al tenant del contexto actual antes de
     * borrar -- un tenant nunca puede eliminar el archivo de otro (SPRINT-13).
     * Tolera archivo ya inexistente (no lanza), pero SÍ lanza SecurityException
     * ante un intento de borrado cruzado -- eso nunca se ignora en silencio.
     */
    public void delete(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(publicUrl)) return;
        String key = fileUrl.substring(publicUrl.length() + 1);

        String tenantSlug = resolveTenantSlug(TenantSecurityUtils.requireTenantId());
        String[] parts = key.split("/", 3); // [folderPrefix, tenantSlug, resto...]
        if (parts.length < 2 || !parts[1].equals(tenantSlug)) {
            throw new SecurityException("No se puede eliminar un archivo de otro tenant");
        }

        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {}
    }

    /** Resuelve el slug del tenant a partir de su id, para namespacing en R2. */
    private String resolveTenantSlug(Long tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getSlug).orElse("tenant_" + tenantId);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
