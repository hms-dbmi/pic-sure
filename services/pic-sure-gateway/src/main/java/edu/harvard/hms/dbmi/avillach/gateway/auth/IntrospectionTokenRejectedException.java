package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * PSAMA answered 401 to a call the gateway authenticated with its own {@code TOKEN_INTROSPECTION_TOKEN}. User tokens travel in the request
 * body and are judged with a 200 {@code active:false}, so a 401 means PSAMA rejected the gateway's token itself: it expired, is unset, or
 * no longer matches the token saved in {@code auth.application}. Until it is replaced, every authenticated request fails. The message tells
 * an operator how to replace it.
 */
public class IntrospectionTokenRejectedException extends RuntimeException {

    /**
     * @param url the PSAMA endpoint that rejected the token
     */
    public IntrospectionTokenRejectedException(String url) {
        super(
            "PSAMA rejected the gateway's TOKEN_INTROSPECTION_TOKEN (HTTP 401 from " + url
                + "). The token has most likely expired, it is unset, or "
                + "does not match the token saved in auth.application. Users cannot log in or query until it is replaced. To fix it, "
                + "run the 'Configure PIC-SURE Token Introspection Token' Jenkins job, which issues a new token valid for one year and "
                + "saves it to auth.application and to TOKEN_INTROSPECTION_TOKEN in the gateway's environment. Then start PIC-SURE again "
                + "so the gateway picks up the new token. PSAMA's log names the expiration time when expiry is the cause."
        );
    }
}
