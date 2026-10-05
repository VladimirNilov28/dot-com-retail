package ee.bytecore.backend.graphql;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraphQlRequestExceptionResolver implements HandlerExceptionResolver {

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        if (!"/graphql".equals(request.getRequestURI())
                || !(exception instanceof HttpMessageNotReadableException
                        || exception instanceof ServerWebInputException)) {
            return null;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter()
                    .write("{\"errors\":[{\"message\":\"Invalid GraphQL request body; provide a JSON object "
                            + "with a string query\",\"extensions\":{\"errorType\":\"BAD_REQUEST\"}}]}");
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to write GraphQL bad-request response", error);
        }
        return new ModelAndView();
    }
}
