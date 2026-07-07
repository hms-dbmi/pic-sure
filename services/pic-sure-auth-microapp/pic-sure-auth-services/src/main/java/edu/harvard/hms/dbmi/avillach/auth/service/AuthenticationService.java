package edu.harvard.hms.dbmi.avillach.auth.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public interface AuthenticationService {

    HashMap<String, String> authenticate(Map<String, String> authRequest, String requestHost) throws IOException;

    String getProvider();

    boolean isEnabled();

}
