package edu.harvard.dbmi.avillach.visualization.logging;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.UUID;

@Component
public class AuditLoggingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingInterceptor.class);
    private static final String START_TIME_ATTR = "vizStartTime";
    private static final String REQUEST_ID_ATTR = "vizRequestId";

    private final LoggingClient loggingClient;

    public AuditLoggingInterceptor(LoggingClient loggingClient) {
        this.loggingClient = loggingClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        request.setAttribute(REQUEST_ID_ATTR, requestId);
        MDC.put("requestId", requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        String requestId = (String) request.getAttribute(REQUEST_ID_ATTR);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        String requestSource = request.getHeader("request-source");
        String path = request.getRequestURI();
        int status = response.getStatus();

        String action = path.contains("/query/sync") ? "query_sync"
            : path.contains("/bin/continuous") ? "bin_continuous" : path.contains("/info") ? "info" : "unknown";

        try {
            LoggingEvent event = LoggingEvent.builder("VISUALIZATION_QUERY").action(action)
                .metadata(
                    Map.of(
                        "duration_ms", duration, "status", status, "access_type", requestSource != null ? requestSource : "unknown", "path",
                        path
                    )
                ).build();
            loggingClient.send(event, null, requestId);
        } catch (Exception e) {
            logger.debug("Failed to send audit log event: {}", e.getMessage());
        }

        MDC.remove("requestId");
    }
}
