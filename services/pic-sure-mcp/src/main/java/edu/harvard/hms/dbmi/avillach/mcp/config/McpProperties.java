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
 * Binds {@code picsure.mcp.*}. Startup fails when the gateway URL, the service token, or the adapter base URL is blank, when the gateway
 * URL is not a bare origin (see {@link #requireGatewayUrl}), or when the adapter base URL is not a bare site URL (see
 * {@link Adapter#requireSiteUrl}). A rejected URL is named in the startup error with its user info replaced by {@value #REDACTED}.
 *
 * @param gatewayUrl base URL of the PIC-SURE gateway every outbound call goes through, from {@code PICSURE_GATEWAY_URL}
 * @param serviceToken the credential sent as {@code X-PIC-SURE-MCP-TOKEN} on every loop-back call, from {@code MCP_SERVICE_TOKEN}
 * @param adapter settings for the adapter code this service generates
 */
@Validated
@ConfigurationProperties("picsure.mcp")
public record McpProperties(@NotBlank String gatewayUrl, @NotBlank String serviceToken, @Valid @NotNull Adapter adapter) {

    /** What a rejected URL's user info is replaced with in a startup error. */
    public static final String REDACTED = "<redacted>";

    private static final String GATEWAY_URL_PROPERTY = "picsure.mcp.gateway-url";

    private static final String BASE_URL_PROPERTY = "picsure.mcp.adapter.base-url";

    /**
     * Checks the gateway URL. A blank one is left to {@code @NotBlank}.
     *
     * @throws IllegalArgumentException if a non-blank gateway URL is not a bare origin, which fails startup
     */
    public McpProperties {
        if (gatewayUrl != null && !gatewayUrl.isBlank()) {
            requireGatewayUrl(gatewayUrl);
        }
    }

    /**
     * Requires a bare gateway origin, because every outbound call is resolved against it and the gateway client refuses any other target.
     * The URL must be absolute with scheme {@code http} or {@code https} in any case, must name a host, and may carry a port but no user
     * info, no query, no fragment, and no path other than {@code /}.
     *
     * @param gatewayUrl the configured gateway URL, not blank
     * @return the parsed gateway URL
     * @throws IllegalArgumentException naming the property and what is wrong with the value
     */
    static URI requireGatewayUrl(String gatewayUrl) {
        URI uri = parse(GATEWAY_URL_PROPERTY, gatewayUrl);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid(GATEWAY_URL_PROPERTY, gatewayUrl, "it must use http or https");
        }
        requireBareOrigin(GATEWAY_URL_PROPERTY, gatewayUrl, uri);
        if (!isRootPath(uri)) {
            throw invalid(GATEWAY_URL_PROPERTY, gatewayUrl, "it must be the gateway origin alone, with no path");
        }
        return uri;
    }

    private static URI parse(String property, String url) {
        if (hasUserInfo(url)) {
            throw invalid(property, url, "it must not carry user info");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid(property, url, "it is not a valid URL");
        }
        if (uri.getScheme() == null || uri.isOpaque()) {
            throw invalid(property, url, "it must be an absolute URL such as https://picsure.example.org");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalid(property, url, "it must name a host");
        }
        return uri;
    }

    private static void requireBareOrigin(String property, String url, URI uri) {
        if (uri.getRawQuery() != null) {
            throw invalid(property, url, "it must not carry a query");
        }
        if (uri.getRawFragment() != null) {
            throw invalid(property, url, "it must not carry a fragment");
        }
    }

    private static boolean isRootPath(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return path.isEmpty() || path.equals("/");
    }

    private static IllegalArgumentException invalid(String property, String url, String reason) {
        return new IllegalArgumentException(property + " \"" + redactUserInfo(url) + "\" is not allowed: " + reason);
    }

    /**
     * Replaces the user info of a URL with {@value #REDACTED}, so a startup error never prints a password. It works on the raw text and
     * needs no valid URL: everything after the scheme separator and before the last {@code @} in the whole value is replaced, even when a
     * {@code /}, {@code ?}, or {@code #} comes first, because a password may hold any of them. This can hide part of a path or query too.
     *
     * @param url the URL as configured
     * @return the URL with any user info replaced
     */
    static String redactUserInfo(String url) {
        int start = authorityStart(url);
        int at = url.lastIndexOf('@');
        if (at < start) {
            return url;
        }
        return url.substring(0, start) + REDACTED + url.substring(at);
    }

    /**
     * Whether a URL with a scheme separator has an {@code @} anywhere after it, the same span {@link #redactUserInfo} replaces. The raw
     * text is checked before parsing, because a password holding {@code /}, {@code ?}, or {@code #} ends the authority early and leaves
     * {@link URI} with no user info and no host.
     *
     * @param url the URL as configured
     * @return true when the URL carries user info
     */
    private static boolean hasUserInfo(String url) {
        return url.contains("://") && url.lastIndexOf('@') >= authorityStart(url);
    }

    private static int authorityStart(String url) {
        int schemeEnd = url.indexOf("://");
        return schemeEnd < 0 ? 0 : schemeEnd + 3;
    }

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
         * Checks the base URL, stores it with its scheme lowercased, and fills in the defaults: {@link #DEFAULT_PYTHON_MIN_VERSION} for a
         * missing or blank Python version, and {@link #DEFAULT_R_TAG} for a missing or blank R tag. A blank base URL is left to
         * {@code @NotBlank}.
         *
         * @throws IllegalArgumentException if a non-blank base URL is not a bare site URL, which fails startup
         */
        public Adapter {
            if (baseUrl != null && !baseUrl.isBlank()) {
                baseUrl = requireSiteUrl(baseUrl);
            }
            pythonMinVersion = pythonMinVersion == null || pythonMinVersion.isBlank() ? DEFAULT_PYTHON_MIN_VERSION : pythonMinVersion;
            rTag = rTag == null || rTag.isBlank() ? DEFAULT_R_TAG : rTag;
        }

        /**
         * Requires a bare site URL, because the user's token is sent to it and it is written into every generated script. The URL must be
         * absolute with scheme {@code https}, or {@code http} on {@code localhost} or {@code 127.0.0.1}, in any case; it must name a host;
         * and it may carry a port but no user info, no query, no fragment, and no path other than {@code /}. A path of {@code /picsure} is
         * refused with its own message, since the adapters add it themselves.
         *
         * @param baseUrl the configured base URL, not blank
         * @return the base URL with its scheme lowercased, so generated code never carries {@code HTTPS://}
         * @throws IllegalArgumentException naming the property and what is wrong with the value
         */
        static String requireSiteUrl(String baseUrl) {
            URI uri = parse(BASE_URL_PROPERTY, baseUrl);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            boolean loopback = LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
            if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
                throw invalid(BASE_URL_PROPERTY, baseUrl, "it must use https, or http only on localhost or 127.0.0.1");
            }
            requireBareOrigin(BASE_URL_PROPERTY, baseUrl, uri);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.equalsIgnoreCase("/picsure") || path.equalsIgnoreCase("/picsure/")) {
                throw invalid(BASE_URL_PROPERTY, baseUrl, "leave off /picsure, because the adapters add it themselves");
            }
            if (!isRootPath(uri)) {
                throw invalid(BASE_URL_PROPERTY, baseUrl, "it must be the site URL alone, with no path");
            }
            return scheme + baseUrl.substring(uri.getScheme().length());
        }
    }
}
