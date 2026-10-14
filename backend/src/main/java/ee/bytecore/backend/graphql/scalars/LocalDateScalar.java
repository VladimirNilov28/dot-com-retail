package ee.bytecore.backend.graphql.scalars;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.*;
import org.jspecify.annotations.NonNull;

@DgsScalar(name = "LocalDate")
public class LocalDateScalar implements Coercing<LocalDate, String> {
    @Override
    public String serialize(
            @NonNull Object dataFetcherResult, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale)
            throws CoercingSerializeException {
        return serializeLocalDate(dataFetcherResult);
    }

    @Override
    public LocalDate parseValue(@NonNull Object input, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale)
            throws CoercingSerializeException {
        return parseLocalDateFromVariable(input);
    }

    @Override
    public LocalDate parseLiteral(
            @NonNull Value<?> input,
            @NonNull CoercedVariables variables,
            @NonNull GraphQLContext graphQLContext,
            @NonNull Locale locale)
            throws CoercingSerializeException {
        return parseLocalDateFromAstLiteral(input);
    }

    private static boolean looksLikeALocalDate(String value) {
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return value.matches("\\d{4}-\\d{2}-\\d{2}");
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String serializeLocalDate(Object dataFetcherResult) {
        if (dataFetcherResult instanceof LocalDate localDate) {
            return localDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        if (dataFetcherResult instanceof String value && looksLikeALocalDate(value)) {
            return value;
        }

        throw new CoercingSerializeException("Value is not a valid LocalDate: '" + dataFetcherResult + "'");
    }

    private static LocalDate parseLocalDateFromVariable(Object input) {
        if (input instanceof String value && looksLikeALocalDate(value)) {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        }

        throw new CoercingParseValueException("Value is not a valid LocalDate: '" + input + "'");
    }

    private static LocalDate parseLocalDateFromAstLiteral(Value<?> input) {
        if (input instanceof StringValue stringValue) {
            String value = stringValue.getValue();

            if (looksLikeALocalDate(value)) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }

        throw new CoercingParseLiteralException("Value is not a valid LocalDate: '" + input + "'");
    }
}
