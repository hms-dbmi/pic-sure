package edu.harvard.hms.dbmi.avillach.gateway.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;

/**
 * The gateway's exception-to-HTTP mapping. Inherits the shared {@code {errorType, message, requestId}} body, the {@code PicsureException}
 * mapping and the client-error and catch-all handling from {@link PicsureErrorBodyAdvice} and {@link PicsureExceptionAdvice}, and adds one
 * gateway-specific mapping for {@link ResponseStatusException}.
 *
 * <p>Before the gateway had an advice, an unmapped exception fell through to Boot's {@code BasicErrorController} and answered
 * {@code {timestamp,status,error,path}}, a shape that names neither the failure nor the request id, and is indistinguishable from an error
 * raised by any other Spring app in the chain.
 *
 * <p>NOTE this cannot catch everything the gateway does: the auth chain runs as servlet FILTERS, and an exception thrown in a filter never
 * reaches Spring MVC's exception resolvers. Those paths fail closed by writing {@link GatewayErrors} directly (see
 * {@code PsamaIntrospectionFilter} / {@code BufferingFilter}); this advice covers the routed/dispatched side.
 */
@RestControllerAdvice
public class GatewayExceptionHandler extends PicsureErrorBodyAdvice {

    /**
     * Preserves the status and reason the gateway chose when it threw a {@code ResponseStatusException}, instead of replacing a deliberate
     * reason with the fixed text for its status. Without a reason, the response carries the fixed text like any other framework error.
     *
     * @param e the exception
     * @param request the current request
     * @return the response with the exception's status
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> statusException(ResponseStatusException e, WebRequest request) {
        String reason = e.getBody().getDetail();
        if (reason == null || reason.isBlank()) {
            return handleExceptionInternal(e, null, e.getHeaders(), e.getStatusCode(), request);
        }
        return ResponseEntity.status(e.getStatusCode()).body(body(errorType(e.getStatusCode()), reason));
    }
}
