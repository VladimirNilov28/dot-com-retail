package ee.bytecore.backend.graphql.scalars;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import graphql.scalars.ExtendedScalars;
import graphql.schema.idl.RuntimeWiring;

@DgsComponent
public class GraphQLConfig {
  @DgsRuntimeWiring
  public RuntimeWiring.Builder addScalars(RuntimeWiring.Builder builder) {
    return builder
        .scalar(ExtendedScalars.Url)
        .scalar(ExtendedScalars.UUID)
        .scalar(ExtendedScalars.GraphQLBigDecimal)
        .scalar(ExtendedScalars.Json);
  }
}
