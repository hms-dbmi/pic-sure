package edu.harvard.hms.dbmi.avillach.hpds.processing.audit;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads {@link AuditEvent} annotations from controller methods and sets
 * the event type and action as request attributes for the {@link AuditLoggingFilter}.
 */
public class AuditInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            AuditEvent auditEvent = handlerMethod.getMethodAnnotation(AuditEvent.class);
            if (auditEvent != null) {
                request.setAttribute(AuditAttributes.EVENT_TYPE, auditEvent.type());
                request.setAttribute(AuditAttributes.ACTION, auditEvent.action());
            }
        }
        return true;
    }
}
