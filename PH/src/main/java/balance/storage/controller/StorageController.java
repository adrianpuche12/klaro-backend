package balance.storage.controller;

import balance.storage.service.R2StorageService;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/uploads")
@CrossOrigin(origins = "*")
public class StorageController {

    @Autowired
    private R2StorageService r2;

    /**
     * Sube un comprobante (foto de transacción) a Cloudflare R2.
     * Devuelve la URL pública de la imagen.
     *
     * POST /api/v2/uploads/comprobante
     * Content-Type: multipart/form-data
     * Body: file (imagen)
     *
     * Response: { "url": "https://pub-xxx.r2.dev/comprobantes/IMG_123.jpg" }
     */
    @PostMapping("/comprobante")
    public ResponseEntity<?> uploadComprobante(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El archivo está vacío"));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solo se permiten imágenes"));
        }

        Long tenantId = TenantSecurityUtils.requireTenantId();
        String folder = "comprobantes/tenant_" + tenantId;

        try {
            String url = r2.upload(file, folder);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al subir la imagen: " + e.getMessage()));
        }
    }
}
