package ee.bytecore.backend.graphql.scalars;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.jspecify.annotations.NonNull;

@DgsScalar(name = "Instant")
public class InstantScalar implements Coercing<Instant, String> {
  @Override
  public String serialize(
      @org.jspecify.annotations.NonNull Object dataFetcherResult,
      @org.jspecify.annotations.NonNull GraphQLContext graphQLContext,
      @org.jspecify.annotations.NonNull Locale locale) {
    return serializeInstant(dataFetcherResult);
  }

  @Override
  public Instant parseValue(
      @NonNull Object input, @NonNull GraphQLContext graphQLContext, @NonNull Locale locale) {
    return parseInstantFromVariable(input);
  }

  @Override
  public Instant parseLiteral(
      @org.jspecify.annotations.NonNull Value<?> input,
      @org.jspecify.annotations.NonNull CoercedVariables variables,
      @org.jspecify.annotations.NonNull GraphQLContext graphQLContext,
      @NonNull Locale locale) {
    return parseInstantFromAstLiteral(input);
  }

  private static boolean looksLikeAnInstant(String value) {
    try {
      Instant.parse(value);
      return value.matches(
          "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})");
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static String serializeInstant(Object dataFetcherResult) {
    if (dataFetcherResult instanceof Instant instant) {
      return instant.toString();
    }

    if (dataFetcherResult instanceof String value && looksLikeAnInstant(value)) {
      return value;
    }

    throw new CoercingSerializeException(
        "Value is not a valid Instant: '" + dataFetcherResult + "'");
  }

  private static Instant parseInstantFromVariable(Object input) {
    if (input instanceof String value && looksLikeAnInstant(value)) {
      return Instant.parse(value);
    }

    throw new CoercingParseValueException("Value is not a valid Instant: '" + input + "'");
  }

  private static Instant parseInstantFromAstLiteral(Value<?> input) {
    if (input instanceof StringValue stringValue) {
      String value = stringValue.getValue();

      if (looksLikeAnInstant(value)) {
        return Instant.parse(value);
      }
    }

    throw new CoercingParseLiteralException("Value is not a valid Instant: '" + input + "'");
  }
}
