package balance.storage.controller;

import balance.storage.service.ComprobanteAccessService;
import balance.storage.service.ComprobanteFileType;
import balance.storage.service.R2StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v2/uploads")
@CrossOrigin(origins = "*")
public class StorageController {

    @Autowired
    private R2StorageService r2;

    @Autowired
    private ComprobanteAccessService comprobanteAccess;

    /**
     * Sube un comprobante a Cloudflare R2 (bucket privado).
     * Devuelve la CLAVE del objeto, que es lo que se guarda en el {@code imageUri}
     * del registro. Para verlo hay que pedir una URL firmada a
     * {@code GET /api/v2/uploads/comprobante/url}.
     *
     * POST /api/v2/uploads/comprobante
     * Content-Type: multipart/form-data
     * Body: file (PDF, JPG o PNG, máx. 10MB), storeId (local al que se adjunta)
     *
     * Response: { "key": "prod/mi-tenant/comprobantes/1700000000000_abcd1234.jpg",
     *             "url": (igual a key; alias deprecado para frontends viejos) }
     */
    @PostMapping("/comprobante")
    public ResponseEntity<?> uploadComprobante(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "storeId", required = false) Long storeId) {
        comprobanteAccess.assertCanUpload(storeId);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El archivo está vacío"));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se pudo leer el archivo"));
        }

        // El tipo se decide por el contenido real, no por el Content-Type ni la extensión del cliente.
        Optional<ComprobanteFileType> type = ComprobanteFileType.detect(bytes);
        if (type.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solo se aceptan comprobantes PDF, JPG o PNG"));
        }

        try {
            String key = r2.upload(bytes, type.get(), "comprobantes");
            return ResponseEntity.ok(Map.of("key", key, "url", key));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al subir el comprobante"));
        }
    }

    /**
     * Devuelve una URL firmada (5 minutos) para ver un comprobante.
     *
     * GET /api/v2/uploads/comprobante/url?ref=<clave o URL pública vieja>
     *
     * {@code ref} acepta la clave nueva o la URL pública completa que guardaban los
     * registros viejos ({@code https://pub-…r2.dev/<clave>}). Valida que el objeto
     * sea del tenant del token (prefijo) y que el usuario tenga acceso al local del
     * registro que lo tiene adjunto.
     *
     * Response: { "url": "https://…?X-Amz-Signature=…", "expiresInSeconds": 300 }
     */
    @GetMapping("/comprobante/url")
    public ResponseEntity<?> comprobanteUrl(@RequestParam("ref") String ref) {
        String key = r2.resolveTenantKey(ref);

        Set<String> refs = new LinkedHashSet<>();
        refs.add(key);
        String legacyUrl = r2.legacyPublicUrl(key);
        if (legacyUrl != null) {
            refs.add(legacyUrl);
        }
        comprobanteAccess.assertCanView(refs);

        return ResponseEntity.ok(Map.of(
                "url", r2.presignedGetUrl(key),
                "expiresInSeconds", R2StorageService.PRESIGNED_TTL.toSeconds()));
    }
}
