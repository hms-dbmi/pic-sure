package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;

final class BannerPageTargets {

    private static final Pattern PARAMETER_SEGMENT = Pattern.compile("\\[[A-Za-z_][A-Za-z0-9_]*]");
    private static final Comparator<BannerPageTarget> CANONICAL_ORDER =
        Comparator.comparingInt((BannerPageTarget target) -> canonicalRank(target.kind()))
            .thenComparing(BannerPageTarget::path, Comparator.nullsFirst(String::compareTo));

    private BannerPageTargets() {}

    static List<BannerPageTarget> normalize(List<BannerPageTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw PicsureExceptions.badRequest("At least one page target is required");
        }
        List<BannerPageTarget> normalized = targets.stream().map(BannerPageTargets::normalize).distinct().sorted(CANONICAL_ORDER).toList();
        if (normalized.stream().anyMatch(target -> target.kind() == BannerPageTargetKind.ALL) && normalized.size() != 1) {
            throw PicsureExceptions.badRequest("All pages cannot be combined with targeted pages");
        }
        return normalized;
    }

    private static BannerPageTarget normalize(BannerPageTarget target) {
        if (target == null || target.kind() == null) {
            throw PicsureExceptions.badRequest("Each page target needs a kind");
        }
        if (target.kind() == BannerPageTargetKind.ALL) {
            if (target.path() != null) {
                throw PicsureExceptions.badRequest("All-pages targets must not include a path");
            }
            return BannerPageTarget.all();
        }
        if (target.path() == null) {
            throw PicsureExceptions.badRequest("Targeted pages need a path");
        }

        String path = normalizePath(target.path());
        List<String> segments = path.equals("/") ? List.of() : List.of(path.substring(1).split("/", -1));
        if (segments.stream().anyMatch(String::isEmpty)) {
            throw PicsureExceptions.badRequest("Page target paths must not contain empty segments");
        }
        if (segments.stream().anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw PicsureExceptions.badRequest("Page target paths must not contain dot segments");
        }

        switch (target.kind()) {
            case EXACT -> requireLiteralSegments(segments);
            case SUBTREE -> {
                if (path.equals("/")) {
                    throw PicsureExceptions.badRequest("Use All pages instead of a root subtree");
                }
                requireLiteralSegments(segments);
            }
            case PARAMETERIZED -> validateParameterizedSegments(segments);
            case ALL -> throw new IllegalStateException("All-pages targets were already handled");
        }
        return new BannerPageTarget(target.kind(), path);
    }

    private static String normalizePath(String submitted) {
        int start = 0;
        int end = submitted.length();
        while (start < end && submitted.charAt(start) == ' ') {
            start++;
        }
        while (end - start > 1 && (submitted.charAt(end - 1) == ' ' || submitted.charAt(end - 1) == '/')) {
            end--;
        }
        String path = submitted.substring(start, end);
        if (path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
            throw PicsureExceptions.badRequest("Page target paths contain unsupported characters");
        }
        if (!path.startsWith("/")) {
            throw PicsureExceptions.badRequest("Page target paths must start with /");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw PicsureExceptions.badRequest("Page target paths must not contain a query or fragment");
        }
        return path;
    }

    private static int canonicalRank(BannerPageTargetKind kind) {
        return switch (kind) {
            case ALL -> 0;
            case EXACT -> 1;
            case PARAMETERIZED -> 2;
            case SUBTREE -> 3;
        };
    }

    static List<BannerPageTarget> fromStoredJson(JsonNode stored) {
        if (stored == null || !stored.isArray()) {
            return null;
        }

        try {
            Set<BannerPageTarget> targets = new LinkedHashSet<>();
            stored.forEach(value -> targets.add(BannerPageTarget.fromJson(value)));
            return normalize(List.copyOf(targets));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static JsonNode toStoredJson(List<BannerPageTarget> targets) {
        ArrayNode stored = JsonNodeFactory.instance.arrayNode();
        for (BannerPageTarget target : targets) {
            ObjectNode value = stored.addObject().put("kind", target.kind().name());
            if (target.path() != null) {
                value.put("path", target.path());
            }
        }
        return stored;
    }

    private static void requireLiteralSegments(List<String> segments) {
        if (segments.stream().anyMatch(segment -> segment.indexOf('[') >= 0 || segment.indexOf(']') >= 0 || segment.indexOf('*') >= 0)) {
            throw PicsureExceptions.badRequest("Exact and subtree targets do not support parameter or wildcard syntax");
        }
    }

    private static void validateParameterizedSegments(List<String> segments) {
        boolean hasParameter = false;
        for (String segment : segments) {
            if (PARAMETER_SEGMENT.matcher(segment).matches()) {
                hasParameter = true;
            } else if (segment.indexOf('[') >= 0 || segment.indexOf(']') >= 0 || segment.indexOf('*') >= 0) {
                throw PicsureExceptions.badRequest("Only plain [name] parameter segments are supported");
            }
        }
        if (!hasParameter) {
            throw PicsureExceptions.badRequest("Parameterized targets need at least one plain [name] segment");
        }
    }
}
