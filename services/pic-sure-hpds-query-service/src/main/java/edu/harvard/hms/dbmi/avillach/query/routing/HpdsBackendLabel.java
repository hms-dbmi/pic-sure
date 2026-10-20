package edu.harvard.hms.dbmi.avillach.query.routing;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Holds the current request's HPDS backend label ({@value HpdsRouter#BACKEND_RUST}, {@value HpdsRouter#BACKEND_JAVA_FALLBACK} or
 * {@value HpdsRouter#BACKEND_JAVA_SHADOW}) as a request attribute. {@link HpdsRouter} records it as soon as the backend is known, before
 * any Java call that may fail, so {@link HpdsBackendHeaderAdvice} can put it on the response whether the handler returns an answer or an
 * exception handler writes an error.
 */
public final class HpdsBackendLabel {

    /** The request attribute that carries the label. */
    static final String ATTRIBUTE = HpdsBackendLabel.class.getName();

    private HpdsBackendLabel() {}

    /**
     * Records a label on the current request. Does nothing outside a request, such as in a unit test or on a background thread.
     *
     * @param label the label
     */
    static void record(String label) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && label != null) {
            attributes.setAttribute(ATTRIBUTE, label, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** @return the current request's label, or null when the request was not routed or mirrored or there is no request */
    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : (String) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }
}
