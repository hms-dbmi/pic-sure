package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;

@ControllerAdvice
public class FilterPreProcessor implements RequestBodyAdvice {
    @Override
    public boolean supports(
        MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
        HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) throws IOException {
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(
        Object body, HttpInputMessage inputMessage, MethodParameter parameter,
        Type targetType, Class<? extends HttpMessageConverter<?>> converterType
    ) {
        if (body instanceof Filter filter && StringUtils.hasLength(filter.search())) {
            return new Filter(filter.facets(), filter.search().replaceAll("_", "/"), filter.consents());
        }
        return body;
    }

    @Override
    public Object handleEmptyBody(
        Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return body;
    }
}
