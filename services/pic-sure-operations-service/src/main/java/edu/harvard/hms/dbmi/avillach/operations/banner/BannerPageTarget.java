package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BannerPageTarget(BannerPageTargetKind kind, String path) {

    private static final Set<String> ALL_FIELDS = Set.of("kind");
    private static final Set<String> PATH_FIELDS = Set.of("kind", "path");

    public static BannerPageTarget all() {
        return new BannerPageTarget(BannerPageTargetKind.ALL, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static BannerPageTarget fromJson(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Each page target must be an object");
        }
        JsonNode kindValue = value.get("kind");
        if (kindValue == null || !kindValue.isTextual()) {
            throw new IllegalArgumentException("Each page target needs a kind");
        }

        BannerPageTargetKind kind;
        try {
            kind = BannerPageTargetKind.valueOf(kindValue.textValue());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported page target kind", e);
        }

        Set<String> fields = value.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        if (kind == BannerPageTargetKind.ALL) {
            if (!fields.equals(ALL_FIELDS)) {
                throw new IllegalArgumentException("All-pages targets contain only the kind");
            }
            return all();
        }
        if (!fields.equals(PATH_FIELDS) || !value.get("path").isTextual()) {
            throw new IllegalArgumentException("Targeted pages need one text path and no other fields");
        }
        return new BannerPageTarget(kind, value.get("path").textValue());
    }
}
