package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.List;
import java.util.Optional;

/**
 * First-match-wins lookup over an ordered list of {@link AuditRoute}s. Order matters for overlapping patterns (e.g. {@code /query/sync}
 * must be listed before the more general {@code /query}).
 */
public final class AuditRouteTable {

    private final List<AuditRoute> routes;

    public AuditRouteTable(List<AuditRoute> routes) {
        this.routes = List.copyOf(routes);
    }

    public Optional<AuditRoute> match(String path, String method) {
        return routes.stream().filter(route -> route.matches(path, method)).findFirst();
    }
}
