package edu.harvard.dbmi.avillach.logging.service;

import edu.harvard.dbmi.avillach.logging.config.AppConfig;
import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.model.RequestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.entries;

public class AuditLogService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final Logger appLog = LoggerFactory.getLogger(AuditLogService.class);
    private static final int MAX_STRING_LENGTH = 2000;

    private final AppConfig config;
    private final JwtDecodeService jwtDecodeService;

    public AuditLogService(AppConfig config, JwtDecodeService jwtDecodeService) {
        this.config = config;
        this.jwtDecodeService = jwtDecodeService;
    }

    public void logEvent(AuditEvent event, String authorizationHeader, String requestIdHeader) {
        if (event == null) {
            appLog.warn("logEvent called with null event, ignoring");
            return;
        }
        try {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

            // 1. Timestamp
            fields.put("_time", Instant.now().toString());

            // 2. Event fields
            putIfNotNull(fields, "event_type", event.eventType());
            putIfNotNull(fields, "action", event.action());
            putIfNotNull(fields, "client_type", event.clientType());

            // 3. User fields from JWT
            Map<String, Object> userClaims = jwtDecodeService.extractClaims(authorizationHeader);
            fields.putAll(userClaims);

            // 4. Platform fields
            putIfNotNull(fields, "app", config.app());
            putIfNotNull(fields, "platform", config.platform());
            putIfNotNull(fields, "environment", config.environment());
            putIfNotNull(fields, "hostname", config.hostname());

            // 5. Request fields
            flattenRequest(fields, event.request(), requestIdHeader);

            // 6. Metadata and error (nested, only if non-empty)
            if (event.metadata() != null && !event.metadata().isEmpty()) {
                fields.put("metadata", event.metadata());
            }
            if (event.error() != null && !event.error().isEmpty()) {
                fields.put("error", event.error());
            }

            auditLog.info("{}", entries(fields));
        } catch (Exception e) {
            appLog.error("Failed to assemble audit log event", e);
        }
    }

    private void flattenRequest(LinkedHashMap<String, Object> fields, RequestInfo request, String requestIdHeader) {
        if (request != null) {
            // request_id from body takes priority
            if (request.requestId() != null && !request.requestId().isBlank()) {
                fields.put("request_id", truncate(request.requestId()));
            } else if (requestIdHeader != null && !requestIdHeader.isBlank()) {
                fields.put("request_id", truncate(requestIdHeader));
            }

            putIfNotNull(fields, "method", request.method());
            putIfNotNull(fields, "url", request.url());
            putIfNotNull(fields, "query_string", request.queryString());
            putIfNotNull(fields, "src_ip", request.srcIp());
            putIfNotNull(fields, "dest_ip", request.destIp());
            if (request.destPort() != null) {
                fields.put("dest_port", request.destPort());
            }
            putIfNotNull(fields, "http_user_agent", request.httpUserAgent());
            putIfNotNull(fields, "http_content_type", request.httpContentType());
            if (request.status() != null) {
                fields.put("status", request.status());
            }
            if (request.bytes() != null) {
                fields.put("bytes", request.bytes());
            }
            if (request.duration() != null) {
                fields.put("duration", request.duration());
            }
            putIfNotNull(fields, "referrer", request.referrer());
        } else if (requestIdHeader != null && !requestIdHeader.isBlank()) {
            fields.put("request_id", truncate(requestIdHeader));
        }
    }

    private void putIfNotNull(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, truncate(value));
        }
    }

    private static String truncate(String value) {
        if (value != null && value.length() > MAX_STRING_LENGTH) {
            return value.substring(0, MAX_STRING_LENGTH);
        }
        return value;
    }
}
