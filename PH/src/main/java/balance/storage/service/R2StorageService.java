package balance.storage.service;

import balance.tenant.context.TenantSecurityUtils;
import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class R2StorageService {

    /** Vida de una URL firmada para ver un comprobante. */
    public static final Duration PRESIGNED_TTL = Duration.ofMinutes(5);

    @Value("${r2.access-key}")      private String accessKey;
    @Value("${r2.secret-key}")      private String secretKey;
    @Value("${r2.endpoint}")        private String endpoint;
    @Value("${r2.bucket}")          private String bucket;
    /** Dominio público viejo (pub-…r2.dev). Solo se usa para reconocer URLs guardadas antes de las URLs firmadas. */
    @Value("${r2.public-url:}")     private String publicUrl;
    @Value("${r2.folder-prefix:prod}") private String folderPrefix;

    @Autowired private TenantRepository tenantRepository;

    private S3Client s3;
    private S3Presigner presigner;

    @PostConstruct
    private void init() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration pathStyle = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();

        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    /**
     * Sube un archivo ya validado a R2 y devuelve la CLAVE del objeto (no una URL):
     * el bucket es privado y para verlo hay que pedir una URL firmada
     * ({@link #presignedGetUrl(String)}).
     * El path incluye el tenant-slug del contexto actual -- aislamiento por
     * tenant (SPRINT-13, ver "03. Arquitectura Multi-Tenancy.md").
     * La extensión y el Content-Type salen del tipo detectado por magic bytes,
     * nunca de lo que declara el cliente.
     *
     * @param bytes  contenido del archivo
     * @param type   tipo real detectado por {@link ComprobanteFileType#detect(byte[])}
     * @param folder carpeta dentro del bucket, relativa al tenant (ej: "comprobantes")
     * @return clave del objeto, ej. {@code prod/mi-tenant/comprobantes/1700000000000_abcd1234.jpg}
     */
    public String upload(byte[] bytes, ComprobanteFileType type, String folder) {
        String key = tenantPrefix() + folder + "/" + Instant.now().toEpochMilli()
                + "_" + UUID.randomUUID().toString().substring(0, 8) + type.extension();
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(type.contentType())
                    .build();

            s3.putObject(req, RequestBody.fromBytes(bytes));
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Error al subir el archivo a R2: " + e.getMessage(), e);
        }
    }

    /**
     * Convierte lo que haya guardado un registro (clave nueva o URL pública vieja
     * {@code https://pub-…r2.dev/<clave>}) en la clave del objeto, validando que
     * pertenezca al tenant del token.
     *
     * @throws ResponseStatusException 404 si no es una clave/URL de nuestro bucket
     * @throws AccessDeniedException   si la clave es de otro tenant
     */
    public String resolveTenantKey(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el comprobante");
        }
        String key = ref.trim();
        if (key.startsWith("http://") || key.startsWith("https://")) {
            String base = publicBase();
            if (base == null || !key.startsWith(base)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado");
            }
            key = key.substring(base.length());
            int query = key.indexOf('?');
            if (query >= 0) {
                key = key.substring(0, query);
            }
        }
        if (key.isEmpty() || key.startsWith("/") || key.contains("..") || key.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado");
        }
        if (!key.startsWith(tenantPrefix())) {
            throw new AccessDeniedException("El comprobante no pertenece a tu empresa");
        }
        return key;
    }

    /**
     * Cómo pudo haber quedado guardada la clave en un registro viejo: la URL pública
     * completa. {@code null} si no hay dominio público configurado.
     */
    public String legacyPublicUrl(String key) {
        String base = publicBase();
        return base == null ? null : base + key;
    }

    /** URL firmada de lectura, válida por {@link #PRESIGNED_TTL}. */
    public String presignedGetUrl(String key) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(key);
        if (!ComprobanteFileType.hasAllowedExtension(key)) {
            // Objeto viejo subido antes de validar por magic bytes (podría ser un SVG/HTML):
            // se fuerza la descarga para que el navegador nunca lo renderice.
            get.responseContentDisposition("attachment")
               .responseContentType("application/octet-stream");
        }
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_TTL)
                .getObjectRequest(get.build())
                .build();
        return presigner.presignGetObject(presign).url().toString();
    }

    /**
     * Elimina un archivo de R2 a partir de su clave o de su URL pública vieja.
     * Valida que el path pertenezca al tenant del contexto actual antes de
     * borrar -- un tenant nunca puede eliminar el archivo de otro (SPRINT-13).
     * Tolera archivo ya inexistente o ajeno al bucket (no lanza), pero SÍ lanza
     * SecurityException ante un intento de borrado cruzado -- eso nunca se ignora en silencio.
     */
    public void delete(String fileRef) {
        if (fileRef == null || fileRef.isBlank()) return;
        String key = fileRef;
        if (fileRef.startsWith("http://") || fileRef.startsWith("https://")) {
            String base = publicBase();
            if (base == null || !fileRef.startsWith(base)) return;
            key = fileRef.substring(base.length());
        }

        String tenantSlug = resolveTenantSlug(TenantSecurityUtils.requireTenantId());
        String[] parts = key.split("/", 3); // [folderPrefix, tenantSlug, resto...]
        if (parts.length < 2 || !parts[1].equals(tenantSlug) || key.contains("..")) {
            throw new SecurityException("No se puede eliminar un archivo de otro tenant");
        }

        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {}
    }

    /** {@code <folderPrefix>/<tenantSlug>/} del tenant del token. */
    private String tenantPrefix() {
        String tenantSlug = resolveTenantSlug(TenantSecurityUtils.requireTenantId());
        return folderPrefix + "/" + tenantSlug + "/";
    }

    private String publicBase() {
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }
        return publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
    }

    /** Resuelve el slug del tenant a partir de su id, para namespacing en R2. */
    private String resolveTenantSlug(Long tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getSlug).orElse("tenant_" + tenantId);
    }
}
