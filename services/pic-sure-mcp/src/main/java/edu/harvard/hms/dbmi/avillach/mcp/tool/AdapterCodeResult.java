package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * What {@code get_adapter_code} returns: the code and, as separate fields, what it needs, how to check for it, and how to install it, so an
 * agent can act on each. {@code warnings} appears only when a concept check found something to report.
 *
 * @param language the language of the code
 * @param requires the connector the code needs
 * @param check a command that prints the installed connector version
 * @param install a command that installs the connector, to show the user before running it
 * @param code the code for the user to run in their own environment
 * @param warnings concept paths the dictionary did not know, or a note that the check could not run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdapterCodeResult(
    @Schema(description = "python, r, or bash") String language, @Schema(description = "The connector the code needs") Requires requires,
    @Schema(description = "A command that prints the installed connector version; run it before the code") String check,
    @Schema(description = "A command that installs the connector; show it to the user and run it only after they confirm") String install,
    @Schema(description = "The code for the user to run in their own environment") String code,
    @Schema(
        requiredMode = NOT_REQUIRED, description = "Concept paths the dictionary did not know, or why they could not be checked"
    ) List<String> warnings
) {

    /**
     * The connector a piece of generated code needs.
     *
     * @param packageName the connector package
     * @param minVersion the oldest connector release the code supports
     * @param runtime the runtime the connector needs
     */
    public record Requires(
        @JsonProperty("package") @Schema(description = "The connector package") String packageName,
        @Schema(description = "The oldest connector release the code supports") String minVersion,
        @Schema(description = "The runtime the connector needs") String runtime
    ) {
    }

    /**
     * The setup as short lines for the model: what the code needs, the check, the install command, how to hand the code over, and any
     * warnings. The code itself is not included.
     *
     * @return the setup text, lines separated by newlines
     */
    public String setupText() {
        List<String> lines = new ArrayList<>();
        lines.add("Requires " + requires.packageName() + " >= " + requires.minVersion() + " (" + requires.runtime() + ").");
        lines.add("Check: " + check);
        lines.add("Install, only after the user confirms, if the check fails or shows an older version: " + install);
        lines.add(
            "Show the code to the user. It runs in the user's environment with their token from PICSURE_TOKEN. Do not run it unless the user asks."
        );
        if (warnings != null) {
            warnings.forEach(warning -> lines.add("Warning: " + warning));
        }
        return String.join("\n", lines);
    }
}
