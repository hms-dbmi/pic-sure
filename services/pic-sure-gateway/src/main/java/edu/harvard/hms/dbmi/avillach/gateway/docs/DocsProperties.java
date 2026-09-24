package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The docs console registry, bound from {@code picsure.gateway.docs.*}. {@code enabled} is the kill switch ({@code GATEWAY_DOCS_ENABLED});
 * {@code uiEnabled} ({@code GATEWAY_DOCS_UI_ENABLED}) removes only the built-in Swagger UI, for environments whose own interface renders
 * the documents; {@code publicBase} is the ingress prefix the per-service public prefixes are built from
 * ({@code GATEWAY_DOCS_PUBLIC_BASE}); each entry of {@code services} is one proxied document. Duplicate names fail binding, and so startup.
 */
@ConfigurationProperties(prefix = "picsure.gateway.docs")
public record DocsProperties(Boolean enabled, Boolean uiEnabled, String publicBase, List<DocumentedService> services) {

    public DocsProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (uiEnabled == null) {
            uiEnabled = Boolean.TRUE;
        }
        if (publicBase == null) {
            publicBase = "/picsure";
        }
        services = services == null ? List.of() : List.copyOf(services);
        Set<String> seen = new HashSet<>();
        for (DocumentedService service : services) {
            if (!seen.add(service.name())) {
                throw new IllegalArgumentException("duplicate docs service name '" + service.name() + "'");
            }
        }
    }
}
