package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * PSAMA refused the gateway's own {@code TOKEN_INTROSPECTION_TOKEN} and said so in {@link PsamaClient#APPLICATION_TOKEN_REJECTED_HEADER}.
 * Until the token is replaced, every authenticated request fails. The message names PSAMA's reason and tells an operator how to replace the
 * token. A 401 without that header never produces this exception, because it may come from a proxy or another failure.
 */
public class IntrospectionTokenRejectedException extends RuntimeException {

    /**
     * @param url the PSAMA endpoint that rejected the token
     * @param reason the value of PSAMA's rejection header
     */
    public IntrospectionTokenRejectedException(String url, String reason) {
        super(
            "PSAMA rejected the gateway's TOKEN_INTROSPECTION_TOKEN on " + url + ": " + describe(reason) + " Users cannot log in or query "
                + "until it is replaced. To fix it, run the 'Configure PIC-SURE Token Introspection Token' Jenkins job, which issues a new "
                + "token valid for one year and saves it to auth.application and to TOKEN_INTROSPECTION_TOKEN in the gateway's environment. "
                + "Then start PIC-SURE again so the gateway picks up the new token."
        );
    }

    private static String describe(String reason) {
        return switch (reason) {
            case "application_token_expired" -> "the token has expired. PSAMA's log names the expiration time.";
            case "token_mismatch" -> "the token does not match the one saved in auth.application.";
            case "application_not_found" -> "the token names an application that is not in auth.application.";
            default -> "PSAMA gave the reason '" + reason + "'.";
        };
    }
}
