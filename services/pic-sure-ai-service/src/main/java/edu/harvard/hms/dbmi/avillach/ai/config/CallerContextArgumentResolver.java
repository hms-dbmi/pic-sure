package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves a {@link CallerContext} controller argument, rejecting the request with 401 before the controller body (and so before any
 * Bedrock/MCP call) when the inbound {@code Authorization} header is missing or not {@code Bearer}-prefixed. The gateway has already run
 * PSAMA introspection on this header by the time the request reaches this service (same trust boundary
 * {@code pic-sure-operations-service}'s {@code GatewayUserArgumentResolver} relies on for {@code X-User-*}) -- this check confirms the
 * header this service specifically needs (the raw bearer value, to replay on MCP calls) is actually present, not that its signature is
 * valid.
 */
public class CallerContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        CallerContext caller = CallerContext.from(request);
        String authorization = caller.authorization();
        if (authorization == null || authorization.isBlank() || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or malformed Authorization: Bearer <jwt> header");
        }
        return caller;
    }
}
