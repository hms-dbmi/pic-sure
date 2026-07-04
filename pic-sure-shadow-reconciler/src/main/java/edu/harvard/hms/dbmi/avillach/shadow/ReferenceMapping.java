package edu.harvard.hms.dbmi.avillach.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The reconciler's own, independently-authored canonical reference mapping (Task 11), derived from the PSAMA access-rule audit. This is the
 * source of truth the gateway's canonical output is checked against — it MUST NOT be derived from, or share code with, the gateway's
 * {@code TargetServiceResolver}, otherwise a resolver bug would simply match itself instead of surfacing as a divergence.
 */
public final class ReferenceMapping {

    /** One prefix rule: a raw path prefix, its canonical rewrite, and whether the rewrite affects PSAMA consent. */
    public record Rule(String rawPrefix, String canonical, RouteMode mode) {
    }

    private final List<Rule> rules; // sorted longest-prefix-first so the most specific rule wins

    private ReferenceMapping(List<Rule> rules) {
        this.rules =
            rules.stream().sorted(Comparator.comparingInt((Rule r) -> r.rawPrefix().length()).reversed()).collect(Collectors.toList());
    }

    /** Loads the rule table from a YAML stream shaped like {@code target-service-mapping.yml}. */
    public static ReferenceMapping load(InputStream yml) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            JsonNode root = mapper.readTree(yml);
            List<Rule> rules = new ArrayList<>();
            JsonNode rulesNode = root.get("rules");
            if (rulesNode != null) {
                for (JsonNode n : rulesNode) {
                    rules
                        .add(new Rule(n.get("rawPrefix").asText(), n.get("canonical").asText(), RouteMode.valueOf(n.get("mode").asText())));
                }
            }
            return new ReferenceMapping(rules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Rule> match(String rawPath) {
        return rules.stream().filter(r -> rawPath.startsWith(r.rawPrefix())).findFirst();
    }

    /** Rewrites a raw request path to its canonical form, or returns it unchanged if no rule matches. */
    public String canonical(String rawPath) {
        return match(rawPath).map(r -> r.canonical() + rawPath.substring(r.rawPrefix().length())).orElse(rawPath);
    }

    /** The {@link RouteMode} for a raw request path; unmapped paths default to {@link RouteMode#COSMETIC}. */
    public RouteMode mode(String rawPath) {
        return match(rawPath).map(Rule::mode).orElse(RouteMode.COSMETIC);
    }
}
