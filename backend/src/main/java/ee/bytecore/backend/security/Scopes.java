package ee.bytecore.backend.security;

/**
 * OAuth2 scope name constants, mirroring {@code infrastructure/oauth/access-control.yml}
 * (the source of truth for the scope spec). Used in {@code @PreAuthorize} SpEL
 * expressions as {@code hasAuthority("SCOPE_" + Scopes.X)} to avoid scattering raw,
 * typo-prone scope strings across DataFetchers/controllers.
 */
public final class Scopes {

    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String USER_MANAGE_ROLE = "user:manage-role";

    public static final String PRODUCT_READ = "product:read";
    public static final String PRODUCT_WRITE = "product:write";

    public static final String CATEGORY_READ = "category:read";
    public static final String CATEGORY_WRITE = "category:write";

    public static final String CART_READ = "cart:read";
    public static final String CART_WRITE = "cart:write";

    public static final String WISHLIST_READ = "wishlist:read";
    public static final String WISHLIST_WRITE = "wishlist:write";

    public static final String WAREHOUSE_READ = "warehouse:read";
    public static final String WAREHOUSE_WRITE = "warehouse:write";

    public static final String ORDER_READ = "order:read";
    public static final String ORDER_WRITE = "order:write";
    public static final String ORDER_MANAGE_STATUS = "order:manage-status";

    public static final String PAYMENT_READ = "payment:read";
    public static final String PAYMENT_WRITE = "payment:write";
    public static final String PAYMENT_MANAGE_STATUS = "payment:manage-status";

    public static final String INTERNAL_PROVISION_USER = "internal:provision-user";

    private Scopes() {}
}
