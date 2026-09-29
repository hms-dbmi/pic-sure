package edu.harvard.hms.dbmi.avillach.mcp.codegen;

/**
 * Code written by a {@link LanguageGenerator}, with the setup the user needs before running it.
 *
 * @param language the language the code is in
 * @param packageName the connector package the code needs, or {@code none} when it needs no adapter
 * @param minVersion the oldest connector release the code supports
 * @param runtime the runtime the connector needs, for example {@code Python >= 3.10}
 * @param check a command that prints the installed connector version
 * @param install a command that installs the connector
 * @param code the code, ending with a newline
 */
public record GeneratedCode(
    Language language, String packageName, String minVersion, String runtime, String check, String install, String code
) {
}
