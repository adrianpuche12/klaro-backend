package balance.storage.controller;

import balance.storage.service.ComprobanteAccessService;
import balance.storage.service.ComprobanteFileType;
import balance.storage.service.R2StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StorageController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class StorageControllerTest {

    private static final byte[] PNG  = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] JPG  = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PDF  = "%PDF-1.7\n%âãÏÓ\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SVG  = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTML = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

    private static final String KEY = "dev/acme/comprobantes/1700000000000_abcd1234.png";

    @Autowired private MockMvc mockMvc;

    @MockBean private R2StorageService r2;
    @MockBean private ComprobanteAccessService comprobanteAccess;

    // ── POST /api/v2/uploads/comprobante ────────────────────────────────────

    @Test
    void upload_png_returnsKey() throws Exception {
        when(r2.upload(any(), eq(ComprobanteFileType.PNG), eq("comprobantes"))).thenReturn(KEY);

        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto.png", "image/png", PNG))
                        .param("storeId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY));

        verify(comprobanteAccess).assertCanUpload(5L);
    }

    @Test
    void upload_jpg_detectedByContent_evenWithWrongClientContentType() throws Exception {
        when(r2.upload(any(), eq(ComprobanteFileType.JPEG), eq("comprobantes"))).thenReturn(KEY);

        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto", "application/octet-stream", JPG))
                        .param("storeId", "5"))
                .andExpect(status().isOk());

        verify(r2).upload(any(), eq(ComprobanteFileType.JPEG), eq("comprobantes"));
    }

    @Test
    void upload_pdf_returnsKey() throws Exception {
        when(r2.upload(any(), eq(ComprobanteFileType.PDF), eq("comprobantes"))).thenReturn("dev/acme/comprobantes/1_a.pdf");

        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "factura.pdf", "application/pdf", PDF))
                        .param("storeId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("dev/acme/comprobantes/1_a.pdf"));
    }

    @Test
    void upload_svg_is400() throws Exception {
        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "logo.svg", "image/svg+xml", SVG))
                        .param("storeId", "5"))
                .andExpect(status().isBadRequest());

        verify(r2, never()).upload(any(), any(), any());
    }

    @Test
    void upload_svgDisguisedAsPng_is400() throws Exception {
        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto.png", "image/png", SVG))
                        .param("storeId", "5"))
                .andExpect(status().isBadRequest());

        verify(r2, never()).upload(any(), any(), any());
    }

    @Test
    void upload_htmlDisguisedAsJpg_is400() throws Exception {
        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", HTML))
                        .param("storeId", "5"))
                .andExpect(status().isBadRequest());

        verify(r2, never()).upload(any(), any(), any());
    }

    @Test
    void upload_withoutStore_is403() throws Exception {
        doThrow(new AccessDeniedException("Debés especificar un local"))
                .when(comprobanteAccess).assertCanUpload(isNull());

        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto.png", "image/png", PNG)))
                .andExpect(status().isForbidden());

        verify(r2, never()).upload(any(), any(), any());
    }

    @Test
    void upload_withoutModuleOrStoreAccess_is403() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(comprobanteAccess).assertCanUpload(5L);

        mockMvc.perform(multipart("/api/v2/uploads/comprobante")
                        .file(new MockMultipartFile("file", "foto.png", "image/png", PNG))
                        .param("storeId", "5"))
                .andExpect(status().isForbidden());

        verify(r2, never()).upload(any(), any(), any());
    }

    // ── GET /api/v2/uploads/comprobante/url ─────────────────────────────────

    @Test
    void url_ownKey_returnsPresignedUrlOfFiveMinutes() throws Exception {
        when(r2.resolveTenantKey(KEY)).thenReturn(KEY);
        when(r2.legacyPublicUrl(KEY)).thenReturn("https://pub-test.r2.dev/" + KEY);
        when(r2.presignedGetUrl(KEY)).thenReturn("https://r2.example.com/" + KEY + "?X-Amz-Signature=abc");

        mockMvc.perform(get("/api/v2/uploads/comprobante/url").param("ref", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://r2.example.com/" + KEY + "?X-Amz-Signature=abc"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));

        // Busca el registro dueño por las dos formas en que pudo quedar guardado.
        verify(comprobanteAccess).assertCanView(Set.of(KEY, "https://pub-test.r2.dev/" + KEY));
    }

    @Test
    void url_legacyPublicUrl_isAccepted() throws Exception {
        String legacy = "https://pub-test.r2.dev/" + KEY;
        when(r2.resolveTenantKey(legacy)).thenReturn(KEY);
        when(r2.legacyPublicUrl(KEY)).thenReturn(legacy);
        when(r2.presignedGetUrl(KEY)).thenReturn("https://r2.example.com/firmada");

        mockMvc.perform(get("/api/v2/uploads/comprobante/url").param("ref", legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://r2.example.com/firmada"));
    }

    @Test
    void url_keyOfAnotherTenant_is403() throws Exception {
        when(r2.resolveTenantKey(anyString())).thenThrow(new AccessDeniedException("El comprobante no pertenece a tu empresa"));

        mockMvc.perform(get("/api/v2/uploads/comprobante/url").param("ref", "dev/otraempresa/comprobantes/x.png"))
                .andExpect(status().isForbidden());

        verify(r2, never()).presignedGetUrl(anyString());
    }

    @Test
    void url_notAttachedToAnyRecord_is404() throws Exception {
        when(r2.resolveTenantKey(KEY)).thenReturn(KEY);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado"))
                .when(comprobanteAccess).assertCanView(anySet());

        mockMvc.perform(get("/api/v2/uploads/comprobante/url").param("ref", KEY))
                .andExpect(status().isNotFound());

        verify(r2, never()).presignedGetUrl(anyString());
    }

    @Test
    void url_recordOfStoreWithoutAccess_is403() throws Exception {
        when(r2.resolveTenantKey(KEY)).thenReturn(KEY);
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(comprobanteAccess).assertCanView(anySet());

        mockMvc.perform(get("/api/v2/uploads/comprobante/url").param("ref", KEY))
                .andExpect(status().isForbidden());

        verify(r2, never()).presignedGetUrl(anyString());
    }
}
