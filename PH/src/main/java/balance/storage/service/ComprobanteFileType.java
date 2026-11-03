package balance.storage.service;

import java.util.Optional;

/**
 * Tipos de archivo aceptados como comprobante. Se detectan por los primeros
 * bytes del archivo ("magic bytes"), nunca por el Content-Type ni por la
 * extensión que manda el cliente: esos dos los controla el usuario y con
 * ellos se colaba, por ejemplo, un SVG o un HTML con script.
 */
public enum ComprobanteFileType {

    PDF("application/pdf", ".pdf", new byte[]{'%', 'P', 'D', 'F', '-'}),
    JPEG("image/jpeg", ".jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", ".png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    private final String contentType;
    private final String extension;
    private final byte[] magic;

    ComprobanteFileType(String contentType, String extension, byte[] magic) {
        this.contentType = contentType;
        this.extension = extension;
        this.magic = magic;
    }

    public String contentType() { return contentType; }

    public String extension() { return extension; }

    /** Devuelve el tipo real del archivo, o vacío si no es PDF, JPG ni PNG. */
    public static Optional<ComprobanteFileType> detect(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        for (ComprobanteFileType type : values()) {
            if (startsWith(bytes, type.magic)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** true si la clave termina en una extensión de los tipos aceptados (para objetos viejos). */
    public static boolean hasAllowedExtension(String key) {
        String lower = key.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
