package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.owasp.html.CssSchema;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reduces the terms of service HTML an administrator submits to the markup the frontend renders. The tag, attribute and link scheme
 * allowlists match the frontend's {@code sanitizeHTML}, and the only inline style kept is a theme colour, so a stored document keeps what
 * the editor produces and loses scripts, event handlers, scriptable URLs and every other style.
 */
public final class TermsOfServiceSanitizer {

    private static final List<String> COLOR_PROPERTIES = List.of("color", "background-color", "background");

    private static final List<String> THEME_COLOR_VARIABLES = themeColorVariables();

    private static final CssSchema STYLE_SCHEMA = styleSchema();

    private static final Pattern PADDED_VARIABLE = Pattern.compile("var\\(\\s+(--color-[a-z]+-\\d+)\\s+\\)");

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
        .allowElements(
            "address", "article", "aside", "footer", "header", "h1", "h2", "h3", "h4", "h5", "h6", "hgroup", "main", "nav", "section",
            "blockquote", "dd", "div", "dl", "dt", "figcaption", "figure", "hr", "li", "menu", "ol", "p", "pre", "ul", "a", "abbr", "b",
            "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q", "rb", "rp", "rt", "rtc", "ruby", "s", "samp",
            "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr", "caption", "col", "colgroup", "table", "tbody", "td",
            "tfoot", "th", "thead", "tr", "img"
        ).allowAttributes("id", "class").globally().allowStyling(STYLE_SCHEMA).allowAttributes("href", "target", "title", "rel")
        .onElements("a").allowAttributes("src", "alt", "title", "width", "height").onElements("img").allowAttributes("colspan", "rowspan")
        .onElements("td", "th").allowAttributes("scope").onElements("th").allowUrlProtocols("http", "https", "mailto").toFactory();

    private TermsOfServiceSanitizer() {}

    /**
     * Sanitizes terms of service HTML.
     *
     * @param html the submitted HTML, possibly null
     * @return the HTML with every element, attribute, scheme and style outside the allowlist removed, or null when {@code html} is null.
     *         The underlying policy pads a kept colour variable with spaces, which the frontend would then reject, so they are removed.
     */
    public static String sanitize(String html) {
        return html == null ? null : PADDED_VARIABLE.matcher(POLICY.sanitize(html)).replaceAll("var($1)");
    }

    /**
     * Lists the theme colour custom properties the editor's colour picker can emit.
     */
    private static List<String> themeColorVariables() {
        List<String> variables = new ArrayList<>();
        for (String role : List.of("surface", "primary", "secondary", "tertiary", "success", "error", "warning")) {
            for (String shade : List.of("50", "200", "500", "800", "950")) {
                variables.add("--color-" + role + "-" + shade);
            }
        }
        return List.copyOf(variables);
    }

    /**
     * Builds a style schema that accepts only {@code var(--color-*)} values for the colour properties, which is the only inline style the
     * editor produces and the frontend keeps.
     */
    private static CssSchema styleSchema() {
        Map<String, CssSchema.Property> properties = new HashMap<>();
        properties.put("var(", new CssSchema.Property(0, new HashSet<>(THEME_COLOR_VARIABLES), Map.of()));
        for (String property : COLOR_PROPERTIES) {
            properties.put(property, new CssSchema.Property(0, new HashSet<>(), Map.of("var(", "var(")));
        }
        return CssSchema.withProperties(properties);
    }
}
