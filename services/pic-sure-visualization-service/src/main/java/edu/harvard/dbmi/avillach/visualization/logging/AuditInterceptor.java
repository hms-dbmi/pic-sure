package edu.harvard.dbmi.avillach.visualization.logging;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Hands the resolved handler's audit label to {@link AuditLoggingFilter}. A servlet filter runs before handler mapping, so this interceptor
 * records the label as request attributes and the filter reads them after the chain returns. The filter sends the one event per request.
 */
public class AuditInterceptor implements HandlerInterceptor {

    /**
     * Records the handler's {@code @AuditEvent} type and action.
     *
     * @param request the current request, which receives the audit attributes
     * @param response the current response, unused
     * @param handler the resolved handler; anything other than a {@link HandlerMethod} is left unlabelled
     * @return always {@code true}, so the request proceeds
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            AuditEvent auditEvent = handlerMethod.getMethodAnnotation(AuditEvent.class);
            if (auditEvent != null) {
                request.setAttribute(AuditLoggingContext.EVENT_TYPE_ATTR, auditEvent.type());
                request.setAttribute(AuditLoggingContext.ACTION_ATTR, auditEvent.action());
            }
        }
        return true;
    }
}
