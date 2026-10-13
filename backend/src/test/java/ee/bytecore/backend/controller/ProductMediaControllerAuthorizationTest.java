package ee.bytecore.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.product.ProductImage;
import ee.bytecore.backend.services.ProductImageService;
import ee.bytecore.backend.services.media.InvalidImageException;
import ee.bytecore.backend.services.media.MediaStorage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@WebMvcTest(ProductMediaController.class)
@Import(SecurityConfig.class)
class ProductMediaControllerAuthorizationTest {

    private static final String KEY = "123e4567-e89b-42d3-a456-426614174000.png";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter converter;

    @MockitoBean
    ProductImageService images;

    @MockitoBean
    MediaStorage storage;

    private MockMultipartHttpServletRequestBuilder upload() {
        return multipart("/admin/media/products/5/images")
                .file(new MockMultipartFile("file", "x.png", "image/png", new byte[] {1, 2, 3}));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String scope, String role) {
        return as(scope, role, "aal2");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String scope, String role, String aal) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("7")
                .claim("scope", scope)
                .claim("role", role)
                .claim("aal", aal)
                .build();
        return authentication(converter.convert(jwt));
    }

    @Test
    void staffAtAal1OrWithoutAssuranceClaimIsForbidden() throws Exception {
        mockMvc.perform(upload().with(as("product:write", "ADMIN", "aal1"))).andExpect(status().isForbidden());
        mockMvc.perform(upload().with(as("product:write", "CATALOG_MANAGER", null))).andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    @Test
    void anonymousUploadIsRejected() throws Exception {
        mockMvc.perform(upload()).andExpect(status().isUnauthorized());
        verifyNoInteractions(images);
    }

    @Test
    void customerUploadIsForbidden() throws Exception {
        mockMvc.perform(upload().with(as("product:write", "USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    @Test
    void staffWithoutWriteScopeIsForbidden() throws Exception {
        mockMvc.perform(upload().with(as("product:read", "CATALOG_MANAGER"))).andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    @Test
    void catalogManagerCanUpload() throws Exception {
        ProductImage saved = ProductImage.create(5L, KEY, "image/png", 3, 300, 300, "Lamp", 0);
        org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 9L);
        when(images.upload(eq(5L), any(), any())).thenReturn(saved);
        mockMvc.perform(upload().with(as("product:write", "CATALOG_MANAGER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("/media/products/" + KEY));
    }

    @Test
    void invalidImageMapsToItsStatusWithSafeMessage() throws Exception {
        when(images.upload(anyLong(), any(), any()))
                .thenThrow(new InvalidImageException(415, "Only JPEG and PNG images are supported."));
        mockMvc.perform(upload().with(as("product:write", "ADMIN")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Only JPEG and PNG images are supported."));
    }

    @Test
    void registeredImagesArePublicWithHardenedHeaders() throws Exception {
        ProductImage image = ProductImage.create(5L, KEY, "image/png", 3, 300, 300, "Lamp", 0);
        when(images.findByKey(KEY)).thenReturn(Optional.of(image));
        when(storage.read(KEY)).thenReturn(Optional.of(new byte[] {1}));
        mockMvc.perform(get("/media/products/" + KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void unregisteredOrMalformedKeysAre404() throws Exception {
        when(images.findByKey(KEY)).thenReturn(Optional.empty());
        mockMvc.perform(get("/media/products/" + KEY)).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/products/..%2Fsecret")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/media/products/not-a-key.png")).andExpect(status().isNotFound());
    }
}
