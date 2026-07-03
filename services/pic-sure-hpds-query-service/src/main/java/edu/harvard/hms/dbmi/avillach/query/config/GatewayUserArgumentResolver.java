package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves a {@link GatewayUser} controller argument by re-deriving it from the gateway's {@code X-User-*} headers via
 * {@link GatewayUserResolver#resolve(HttpServletRequest)}. Yields {@code null} when the gateway supplied no identity (no {@code X-User-Id}
 * header) -- controllers that require an identity must null-check and reject accordingly.
 */
public class GatewayUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return GatewayUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        return GatewayUserResolver.resolve(request).orElse(null);
    }
}
