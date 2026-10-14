package ee.bytecore.backend.graphql.datafetchers;

import static ee.bytecore.backend.graphql.datafetchers.support.JwtTestSupport.asAuthenticatedJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.graphql.datafetchers.admin.AdminAuditQuery;
import ee.bytecore.backend.graphql.datafetchers.admin.AdminUserQuery;
import ee.bytecore.backend.graphql.datafetchers.admin.DeliveryOptionDataFetcher;
import ee.bytecore.backend.graphql.datafetchers.admin.ProductImportMutation;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.services.DeliveryOptionService;
import ee.bytecore.backend.services.ProductImportService;
import ee.bytecore.backend.services.UserService;
import ee.bytecore.backend.services.audit.AuditService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.Test;

/**
 * Role and scope negatives for the PROJECT 3 admin operations, against the real
 * SecurityFilterChain + JwtAuthenticationConverter (same pattern as
 * CatalogMutationAuthorizationTest).
 */
@SpringBootTest(
        classes = {
            AdminAuditQuery.class,
            AdminUserQuery.class,
            DeliveryOptionDataFetcher.class,
            ProductImportMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class
        })
@EnableDgsMockMvcTest
class AdminOperationsAuthorizationTest {

    private static final String AUDIT_QUERY = "query { adminAuditLog { totalElements } }";
    private static final String USERS_QUERY = "query { adminUsers { totalElements } }";
    private static final String DELIVERY_QUERY = "query { adminDeliveryOptions { code } }";
    private static final String DELETE_DELIVERY = "mutation { deleteDeliveryOption(id: \\\"1\\\") }";
    private static final String IMPORT = "mutation { importProducts(format: CSV, content: \\\"slug\\\") { success } }";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    AuditService auditService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    DeliveryOptionService deliveryOptionService;

    @MockitoBean
    ProductImportService productImportService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private ResultActions perform(String query, String role, String... scopes) throws Exception {
        return mockMvc.perform(post("/graphql")
                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", role, scopes))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + query + "\"}"));
    }

    @Test
    void shouldRejectAuditLogForCatalogManagerTest() throws Exception {
        perform(AUDIT_QUERY, "CATALOG_MANAGER", "audit:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verify(auditService, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void shouldRejectAuditLogForAdminMissingScopeTest() throws Exception {
        perform(AUDIT_QUERY, "ADMIN", "user:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowAuditLogForAdminTest() throws Exception {
        when(auditService.search(isNull(), isNull(), isNull(), eq(0), eq(25)))
                .thenReturn(new AuditService.EntryPage(List.of(), 0, 0, 25));

        perform(AUDIT_QUERY, "ADMIN", "audit:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.adminAuditLog.totalElements").value(0));
    }

    @Test
    void shouldRejectAdminUsersForCatalogManagerTest() throws Exception {
        perform(USERS_QUERY, "CATALOG_MANAGER", "user:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verify(userService, never()).searchActive(any(), any(), anyInt(), anyInt());
    }

    @Test
    void shouldAllowAdminUsersForAdminTest() throws Exception {
        when(userService.searchActive(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

        perform(USERS_QUERY, "ADMIN", "user:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.adminUsers.totalElements").value(0));
    }

    @Test
    void shouldRejectDeliveryOptionsForCatalogManagerTest() throws Exception {
        perform(DELIVERY_QUERY, "CATALOG_MANAGER", "shipping:manage")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectDeleteDeliveryOptionForAdminMissingScopeTest() throws Exception {
        perform(DELETE_DELIVERY, "ADMIN", "product:write")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verify(deliveryOptionService, never()).delete(any());
    }

    @Test
    void shouldAllowDeliveryOptionsForAdminTest() throws Exception {
        when(deliveryOptionService.findAll()).thenReturn(List.of());

        perform(DELIVERY_QUERY, "ADMIN", "shipping:manage")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.adminDeliveryOptions").isArray());
    }

    @Test
    void shouldRejectImportForPlainUserTest() throws Exception {
        perform(IMPORT, "USER", "product:write")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verify(productImportService, never()).importProducts(any(), anyString());
    }

    @Test
    void shouldRejectImportForCatalogManagerMissingScopeTest() throws Exception {
        perform(IMPORT, "CATALOG_MANAGER", "product:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowImportForCatalogManagerAndAuditItTest() throws Exception {
        when(productImportService.importProducts(eq(ProductImportService.Format.CSV), eq("slug")))
                .thenReturn(new ProductImportService.Result(false, 0, 0, 0, List.of()));

        perform(IMPORT, "CATALOG_MANAGER", "product:write")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.importProducts.success").value(false));
        verify(auditService).record(eq("importProducts"), eq("PRODUCT_IMPORT"), any(), any(), any());
    }
}
