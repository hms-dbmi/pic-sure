package edu.harvard.hms.dbmi.avillach.mcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code picsure.mcp.*}. Startup fails when the gateway URL, the service token, or the adapter base URL is blank.
 *
 * @param gatewayUrl base URL of the PIC-SURE gateway every outbound call goes through, from {@code PICSURE_GATEWAY_URL}
 * @param serviceToken the credential sent as {@code X-PIC-SURE-MCP-TOKEN} on every loop-back call, from {@code MCP_SERVICE_TOKEN}
 * @param adapter settings for the adapter code this service generates
 */
@Validated
@ConfigurationProperties("picsure.mcp")
public record McpProperties(@NotBlank String gatewayUrl, @NotBlank String serviceToken, @Valid @NotNull Adapter adapter) {

    /**
     * Describes the properties without the service token.
     *
     * @return a string safe to write to a log
     */
    @Override
    public String toString() {
        return "McpProperties[gatewayUrl=" + gatewayUrl + ", serviceToken=<redacted>, adapter=" + adapter + "]";
    }

    /**
     * Settings for generated adapter code.
     *
     * @param baseUrl the PIC-SURE URL generated code connects to, from {@code MCP_ADAPTER_BASE_URL}
     * @param includeConsents whether generated code passes {@code include_consents=True}
     * @param supportsGenomic whether generated code passes {@code supports_genomic=True}
     * @param pythonMinVersion the oldest {@code picsure} Python adapter release generated code supports, from
     *        {@code MCP_ADAPTER_PYTHON_MIN_VERSION}, default {@code 3.0.0}
     * @param rTag the R adapter release tag generated R code installs, from {@code MCP_ADAPTER_R_TAG}, default {@code v3.0.0}
     */
    public record Adapter(
        @NotBlank String baseUrl, boolean includeConsents, boolean supportsGenomic,
        @Pattern(regexp = "[0-9][0-9A-Za-z.]*") String pythonMinVersion, @Pattern(regexp = "[0-9A-Za-z._-]*") String rTag
    ) {

        /** The Python adapter release generated code supports when none is configured: the current {@code picsure} release. */
        public static final String DEFAULT_PYTHON_MIN_VERSION = "3.0.0";

        /** The R adapter release tag generated R code installs when none is configured: the current {@code picsure} R release. */
        public static final String DEFAULT_R_TAG = "v3.0.0";

        /**
         * Fills in the defaults: {@link #DEFAULT_PYTHON_MIN_VERSION} for a missing or blank Python version, and {@link #DEFAULT_R_TAG} for
         * a missing or blank R tag.
         */
        public Adapter {
            pythonMinVersion = pythonMinVersion == null || pythonMinVersion.isBlank() ? DEFAULT_PYTHON_MIN_VERSION : pythonMinVersion;
            rTag = rTag == null || rTag.isBlank() ? DEFAULT_R_TAG : rTag;
        }
    }
}
