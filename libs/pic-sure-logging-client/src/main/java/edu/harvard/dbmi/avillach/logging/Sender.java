package edu.harvard.dbmi.avillach.logging;

import java.net.URI;

interface Sender {
    void send(byte[] body, URI auditEndpoint, LoggingClientConfig config, LoggingEvent resolved, String bearerToken, String requestId);
}
