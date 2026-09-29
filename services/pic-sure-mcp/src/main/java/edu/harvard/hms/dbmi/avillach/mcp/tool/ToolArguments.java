package edu.harvard.hms.dbmi.avillach.mcp.tool;

/** Argument checks shared by the tools. Spring AI does not validate arguments against the input schema, so each tool does it. */
public final class ToolArguments {

    /** Longest search text accepted, in characters. */
    public static final int MAX_QUERY_LENGTH = 500;

    private ToolArguments() {}

    /**
     * Requires a non-blank argument of bounded length with no control characters.
     *
     * @param name the argument name, used in the failure message
     * @param value the value the model sent, possibly null
     * @param maxLength the longest accepted length
     * @return the value unchanged
     * @throws ToolFailure if the value is null, blank, too long, or holds a control character
     */
    public static String requireText(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ToolFailure("Argument '" + name + "' is required.");
        }
        return optionalText(name, value, maxLength);
    }

    /**
     * Accepts a missing or bounded argument.
     *
     * @param name the argument name, used in the failure message
     * @param value the value the model sent, possibly null
     * @param maxLength the longest accepted length
     * @return the value, or an empty string when it was null
     * @throws ToolFailure if the value is too long or holds a control character
     */
    public static String optionalText(String name, String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            throw new ToolFailure("Argument '" + name + "' must be at most " + maxLength + " characters.");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new ToolFailure("Argument '" + name + "' must not contain control characters.");
        }
        return value;
    }
}
