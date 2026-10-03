package edu.harvard.hms.dbmi.avillach.mcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Binds {@code picsure.mcp.*}. Startup fails when the gateway URL, the service token, or the adapter base URL is blank, or when the adapter
 * base URL is not a bare site URL (see {@link Adapter#requireSiteUrl}).
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
     * @param baseUrl the PIC-SURE site URL generated code connects to and sends the user's token to, from {@code MCP_ADAPTER_BASE_URL}
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

        /** The hosts on which the base URL may use plain {@code http}. */
        public static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");

        /**
         * Checks the base URL and fills in the defaults: {@link #DEFAULT_PYTHON_MIN_VERSION} for a missing or blank Python version, and
         * {@link #DEFAULT_R_TAG} for a missing or blank R tag. A blank base URL is left to {@code @NotBlank}.
         *
         * @throws IllegalArgumentException if a non-blank base URL is not a bare site URL, which fails startup
         */
        public Adapter {
            if (baseUrl != null && !baseUrl.isBlank()) {
                requireSiteUrl(baseUrl);
            }
            pythonMinVersion = pythonMinVersion == null || pythonMinVersion.isBlank() ? DEFAULT_PYTHON_MIN_VERSION : pythonMinVersion;
            rTag = rTag == null || rTag.isBlank() ? DEFAULT_R_TAG : rTag;
        }

        /**
         * Requires a bare site URL, because the user's token is sent to it and it is written into every generated script. The URL must be
         * absolute with scheme {@code https}, or {@code http} on {@code localhost} or {@code 127.0.0.1}; it must name a host; and it may
         * carry a port but no user info, no query, no fragment, and no path other than {@code /}. A path of {@code /picsure} is refused
         * with its own message, since the adapters add it themselves.
         *
         * @param baseUrl the configured base URL, not blank
         * @throws IllegalArgumentException naming the property and what is wrong with the value
         */
        static void requireSiteUrl(String baseUrl) {
            URI uri;
            try {
                uri = new URI(baseUrl);
            } catch (URISyntaxException e) {
                throw invalid(baseUrl, "it is not a valid URL");
            }
            String scheme = uri.getScheme();
            if (scheme == null || uri.isOpaque()) {
                throw invalid(baseUrl, "it must be an absolute URL such as https://picsure.example.org");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw invalid(baseUrl, "it must name a host");
            }
            boolean loopback = LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
            if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
                throw invalid(baseUrl, "it must use https, or http only on localhost or 127.0.0.1");
            }
            if (uri.getRawUserInfo() != null) {
                throw invalid(baseUrl, "it must not carry user info");
            }
            if (uri.getRawQuery() != null) {
                throw invalid(baseUrl, "it must not carry a query");
            }
            if (uri.getRawFragment() != null) {
                throw invalid(baseUrl, "it must not carry a fragment");
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.equalsIgnoreCase("/picsure") || path.equalsIgnoreCase("/picsure/")) {
                throw invalid(baseUrl, "leave off /picsure, because the adapters add it themselves");
            }
            if (!path.isEmpty() && !path.equals("/")) {
                throw invalid(baseUrl, "it must be the site URL alone, with no path");
            }
        }

        private static IllegalArgumentException invalid(String baseUrl, String reason) {
            return new IllegalArgumentException("picsure.mcp.adapter.base-url \"" + baseUrl + "\" is not allowed: " + reason);
        }
    }
}
