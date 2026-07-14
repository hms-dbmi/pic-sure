package edu.harvard.dbmi.avillach.logging;

import java.net.URI;

final class NoOpSender implements Sender {
    @Override
    public void send(
        byte[] body, URI auditEndpoint, LoggingClientConfig config, LoggingEvent resolved, String bearerToken, String requestId
    ) {
        // no-op
    }
}
