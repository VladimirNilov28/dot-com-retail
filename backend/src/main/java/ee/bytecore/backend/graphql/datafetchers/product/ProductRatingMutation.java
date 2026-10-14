package ee.bytecore.backend.graphql.datafetchers.product;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.ProductMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.ProductRatingService;

import com.netflix.dgs.codegen.generated.types.Product;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class ProductRatingMutation {
    private final ProductRatingService ratings;
    private final CurrentUserProvider currentUser;

    public ProductRatingMutation(ProductRatingService ratings, CurrentUserProvider currentUser) {
        this.ratings = ratings;
        this.currentUser = currentUser;
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public Product rateProduct(@InputArgument String productId, @InputArgument int stars) {
        long id;
        try {
            id = Long.parseLong(productId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid product id: " + productId);
        }
        return ProductMapper.toGraphQlType(ratings.rate(currentUser.getCurrentUserId(), id, stars));
    }
}
