package edu.harvard.dbmi.avillach.logging.handler;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.util.Map;

public class AuditHandler {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private static final int MAX_METADATA_KEYS = 50;
    private static final int MAX_ERROR_KEYS = 20;

    public AuditHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        this.objectMapper = new ObjectMapper();
        objectMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(10)
                .maxStringLength(10_240) // 10KB
                .build()
        );
    }

    public void handle(Context ctx) {
        AuditEvent event;
        try {
            event = objectMapper.readValue(ctx.body(), AuditEvent.class);
        } catch (Exception e) {
            throw new BadRequestResponse("Invalid JSON: " + e.getMessage());
        }

        if (event.metadata() != null && event.metadata().size() > MAX_METADATA_KEYS) {
            throw new BadRequestResponse("metadata must not exceed " + MAX_METADATA_KEYS + " keys");
        }
        if (event.error() != null && event.error().size() > MAX_ERROR_KEYS) {
            throw new BadRequestResponse("error must not exceed " + MAX_ERROR_KEYS + " keys");
        }

        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new BadRequestResponse("event_type is required");
        }

        String authHeader = ctx.header("Authorization");
        String requestIdHeader = ctx.header("X-Request-Id");

        auditLogService.logEvent(event, authHeader, requestIdHeader);

        ctx.status(202);
        ctx.json(Map.of("status", "accepted"));
    }
}
