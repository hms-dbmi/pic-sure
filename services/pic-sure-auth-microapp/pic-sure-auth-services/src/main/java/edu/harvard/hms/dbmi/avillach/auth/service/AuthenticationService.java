package edu.harvard.hms.dbmi.avillach.auth.service;

import java.io.IOException;

import edu.harvard.hms.dbmi.avillach.auth.model.request.AuthenticationRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AuthenticationResponse;

/**
 * One identity provider's half of the login exchange. Both sides are records rather than {@code Map<String, String>}: the request names
 * exactly the credentials the shipped providers read, and the response names the profile keys the endpoint has always emitted -- neither is
 * discoverable from a map, and the endpoint's OpenAPI document could not describe either.
 */
public interface AuthenticationService {

    /**
     * @return the authenticated user's profile, or {@code null} when the provider declines to authenticate
     */
    AuthenticationResponse authenticate(AuthenticationRequest authRequest, String requestHost) throws IOException;

    String getProvider();

    boolean isEnabled();

}
