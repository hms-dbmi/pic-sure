package edu.harvard.hms.dbmi.avillach.query.routing;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Writes the request's {@link HpdsBackendLabel} into the {@value HpdsRouter#BACKEND_HEADER} response header just before any response body
 * is written, the answers of routed handlers and the error bodies of exception handlers alike. The gateway turns the header into the
 * {@code hpds_backend} audit metadata and removes it.
 */
@ControllerAdvice
public class HpdsBackendHeaderAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
        Object body, MethodParameter returnType, MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response
    ) {
        String label = HpdsBackendLabel.current();
        if (label != null) {
            response.getHeaders().set(HpdsRouter.BACKEND_HEADER, label);
        }
        return body;
    }
}
