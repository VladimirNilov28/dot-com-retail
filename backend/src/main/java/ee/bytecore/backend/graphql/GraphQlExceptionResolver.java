package ee.bytecore.backend.graphql;

import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import ee.bytecore.backend.exceptions.CheckoutException;
import ee.bytecore.backend.exceptions.GuestCartUnavailableException;
import ee.bytecore.backend.exceptions.InsufficientStockException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.exceptions.UserNotFoundException;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.EntityNotFoundException;

/**
 * Maps known application exceptions to a controlled GraphQL error instead of
 * letting Spring GraphQL's default handler fall back to the exception's
 * toString() (which leaks the Java class name and raw message for anything
 * unhandled).
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment env) {
        if (exception instanceof CheckoutException checkout) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorClassification.errorClassification(checkout.getCode()))
                    .extensions(java.util.Map.of("errorType", checkout.getCode()))
                    .message(checkout.getMessage())
                    .build();
        }
        if (exception instanceof GuestCartUnavailableException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorClassification.errorClassification("GUEST_CART_UNAVAILABLE"))
                    .extensions(java.util.Map.of("errorType", "GUEST_CART_UNAVAILABLE"))
                    .message(exception.getMessage())
                    .build();
        }
        if (exception instanceof UserNotFoundException || exception instanceof EntityNotFoundException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.NOT_FOUND)
                    .message(exception.getMessage())
                    .build();
        }
        if (exception instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(exception.getMessage())
                    .build();
        }
        if (exception instanceof UserAlreadyExistsException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorClassification.errorClassification("CONFLICT"))
                    .message(exception.getMessage())
                    .build();
        }
        if (exception instanceof InsufficientStockException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorClassification.errorClassification("CONFLICT"))
                    .message(exception.getMessage())
                    .build();
        }
        return null;
    }
}
