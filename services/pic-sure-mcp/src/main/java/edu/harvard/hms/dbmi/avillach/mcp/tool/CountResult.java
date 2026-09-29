package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * One obfuscated open-access count, parsed out of the open channel's display string so the model does not have to read the {@code ±}. A
 * count at or above the threshold arrives as {@code "1234 ±3"}; one below it arrives as {@code "< 10"}. A display string that matches
 * neither is returned alone. Fields that do not apply are omitted from the JSON, since Spring AI rejects a null in
 * {@code structuredContent}.
 *
 * @param display the display string exactly as the open channel returned it, trimmed and capped at {@link #MAX_DISPLAY_LENGTH}
 * @param count the obfuscated count, when the display carries one
 * @param variance the half-width of the band the true count lies in, when the display carries one
 * @param threshold the suppression threshold, when the count is suppressed
 * @param suppressed whether the count was below the threshold and hidden, when the display could be parsed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CountResult(
    @Schema(description = "The open channel's display string") String display,
    @Schema(requiredMode = NOT_REQUIRED, description = "The obfuscated count") Integer count,
    @Schema(requiredMode = NOT_REQUIRED, description = "The true count lies within count plus or minus variance") Integer variance,
    @Schema(requiredMode = NOT_REQUIRED, description = "The true count is below this threshold") Integer threshold,
    @Schema(requiredMode = NOT_REQUIRED, description = "Whether the count is hidden because it is below the threshold") Boolean suppressed
) {

    /** Longest display string kept, in characters. */
    public static final int MAX_DISPLAY_LENGTH = 100;

    private static final Pattern OBFUSCATED = Pattern.compile("(\\d{1,9})\\s*±\\s*(\\d{1,9})");

    private static final Pattern EXACT = Pattern.compile("\\d{1,9}");

    private static final Pattern BELOW_THRESHOLD = Pattern.compile("<\\s*(\\d{1,9})");

    /**
     * Parses a display string. Never fails: an unparseable string comes back as the display alone.
     *
     * @param raw the display string, possibly wrapped in JSON quotes, possibly null
     * @return the parsed count
     */
    public static CountResult parse(String raw) {
        String display = unquote(raw == null ? "" : raw.strip());
        if (display.length() > MAX_DISPLAY_LENGTH) {
            display = display.substring(0, MAX_DISPLAY_LENGTH);
        }
        Matcher obfuscated = OBFUSCATED.matcher(display);
        if (obfuscated.matches()) {
            return new CountResult(display, Integer.parseInt(obfuscated.group(1)), Integer.parseInt(obfuscated.group(2)), null, false);
        }
        if (EXACT.matcher(display).matches()) {
            return new CountResult(display, Integer.parseInt(display), null, null, false);
        }
        Matcher below = BELOW_THRESHOLD.matcher(display);
        if (below.matches()) {
            return new CountResult(display, null, null, Integer.parseInt(below.group(1)), true);
        }
        return new CountResult(display, null, null, null, null);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }
}
