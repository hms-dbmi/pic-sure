package edu.harvard.dbmi.avillach.logging.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuditController {

    private static final int MAX_METADATA_KEYS = 50;
    private static final int MAX_ERROR_KEYS = 20;
    private static final int MAX_NESTING_DEPTH = 10;
    private static final int MAX_STRING_LENGTH = 10_240;

    /**
     * Hardened mapper for parsing /audit bodies. Deliberately NOT a Spring bean: an ObjectMapper bean trips
     * JacksonAutoConfiguration's @ConditionalOnMissingBean and would replace Boot's auto-configured mapper (JavaTimeModule and all) for
     * every response in the context. StreamReadConstraints govern reading only, and this is the service's sole hand-rolled Jackson read
     * path.
     */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper(
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).maxStringLength(MAX_STRING_LENGTH).build()
            ).build()
    );

    private final AuditLogService auditLogService;

    public AuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** Bind as String so valid JSON remains accepted without Content-Type and the dedicated audit mapper controls parsing constraints. */
    @PostMapping("/audit")
    public ResponseEntity<Map<String, String>> audit(
        @RequestBody String body, @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader
    ) {
        AuditEvent event;
        try {
            event = AUDIT_MAPPER.readValue(body, AuditEvent.class);
        } catch (Exception e) {
            throw new BadRequestException("Invalid JSON: " + e.getMessage());
        }

        if (event.metadata() != null && event.metadata().size() > MAX_METADATA_KEYS) {
            throw new BadRequestException("metadata must not exceed " + MAX_METADATA_KEYS + " keys");
        }
        if (event.error() != null && event.error().size() > MAX_ERROR_KEYS) {
            throw new BadRequestException("error must not exceed " + MAX_ERROR_KEYS + " keys");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new BadRequestException("event_type is required");
        }

        auditLogService.logEvent(event, authorizationHeader, requestIdHeader);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "accepted"));
    }
}
