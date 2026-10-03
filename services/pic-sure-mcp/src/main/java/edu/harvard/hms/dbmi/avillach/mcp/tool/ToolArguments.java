package edu.harvard.hms.dbmi.avillach.mcp.tool;

/** Argument checks shared by the tools. Spring AI does not validate arguments against the input schema, so each tool does it. */
public final class ToolArguments {

    /**
     * Longest search text accepted, in characters. The dictionary cuts a longer search to this length before it strips punctuation, so a
     * longer one would be searched only in part.
     */
    public static final int MAX_SEARCH_LENGTH = 200;

    private ToolArguments() {}

    /**
     * Requires a non-blank argument of bounded length with no control characters and no unpaired UTF-16 surrogates.
     *
     * @param name the argument name, used in the failure message
     * @param value the value the model sent, possibly null
     * @param maxLength the longest accepted length
     * @return the value unchanged
     * @throws ToolFailure if the value is null, blank, too long, or holds a control character or an unpaired surrogate
     */
    public static String requireText(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ToolFailure("Argument '" + name + "' is required.");
        }
        return optionalText(name, value, maxLength);
    }

    /**
     * Accepts a missing or bounded argument. An unpaired surrogate is refused because it is not valid text, and generated R code that held
     * one would not parse.
     *
     * @param name the argument name, used in the failure message
     * @param value the value the model sent, possibly null
     * @param maxLength the longest accepted length
     * @return the value, or an empty string when it was null
     * @throws ToolFailure if the value is too long or holds a control character or an unpaired surrogate
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
        if (hasUnpairedSurrogate(value)) {
            throw new ToolFailure("Argument '" + name + "' must not contain an unpaired UTF-16 surrogate.");
        }
        return value;
    }

    /**
     * Tells whether a string holds a high surrogate not followed by a low one, or a low surrogate not preceded by a high one.
     *
     * @param value the string
     * @return true if any surrogate in it is unpaired
     */
    public static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
