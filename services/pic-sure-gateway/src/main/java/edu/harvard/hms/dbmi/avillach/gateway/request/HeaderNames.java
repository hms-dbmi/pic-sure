package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Helpers for the request wrappers that rewrite which headers a downstream filter or proxy sees.
 */
public final class HeaderNames {

    private HeaderNames() {}

    /**
     * Collects the header names a container reports, keeping only those the predicate accepts.
     *
     * @param names the enumeration from {@code getHeaderNames()}, which containers may leave null
     * @param keep accepts the names to retain
     * @return the retained names in encounter order, never null
     */
    public static Set<String> retain(Enumeration<String> names, Predicate<String> keep) {
        Set<String> kept = new LinkedHashSet<>();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (keep.test(name)) {
                kept.add(name);
            }
        }
        return kept;
    }
}
