package edu.harvard.dbmi.avillach.logging;

import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;

/**
 * Names every {@link RequestInfo} field at the call site. Thirteen positional components, five of them adjacent Strings, is a transposition
 * the compiler cannot catch, and every audit filter in the platform builds one.
 *
 * <p>This lives here rather than on the record because it is emitter-side ergonomics, not part of the wire shape:
 * {@code pic-sure-contracts} stays records-and-annotations, and the logging service only ever deserializes a RequestInfo. Every caller that
 * builds one already depends on this library for {@link LoggingEvent}.
 *
 * <p>Unset fields stay null and, per the record's NON_NULL inclusion, stay off the wire.
 */
public final class RequestInfoBuilder {

    private String requestId;
    private String method;
    private String url;
    private String queryString;
    private String srcIp;
    private String destIp;
    private Integer destPort;
    private String httpUserAgent;
    private String httpContentType;
    private Integer status;
    private Long bytes;
    private Long duration;
    private String referrer;

    public RequestInfoBuilder requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public RequestInfoBuilder method(String method) {
        this.method = method;
        return this;
    }

    public RequestInfoBuilder url(String url) {
        this.url = url;
        return this;
    }

    public RequestInfoBuilder queryString(String queryString) {
        this.queryString = queryString;
        return this;
    }

    public RequestInfoBuilder srcIp(String srcIp) {
        this.srcIp = srcIp;
        return this;
    }

    public RequestInfoBuilder destIp(String destIp) {
        this.destIp = destIp;
        return this;
    }

    public RequestInfoBuilder destPort(Integer destPort) {
        this.destPort = destPort;
        return this;
    }

    public RequestInfoBuilder httpUserAgent(String httpUserAgent) {
        this.httpUserAgent = httpUserAgent;
        return this;
    }

    public RequestInfoBuilder httpContentType(String httpContentType) {
        this.httpContentType = httpContentType;
        return this;
    }

    public RequestInfoBuilder status(Integer status) {
        this.status = status;
        return this;
    }

    public RequestInfoBuilder bytes(Long bytes) {
        this.bytes = bytes;
        return this;
    }

    public RequestInfoBuilder duration(Long duration) {
        this.duration = duration;
        return this;
    }

    public RequestInfoBuilder referrer(String referrer) {
        this.referrer = referrer;
        return this;
    }

    public RequestInfo build() {
        return new RequestInfo(
            requestId, method, url, queryString, srcIp, destIp, destPort, httpUserAgent, httpContentType, status, bytes, duration, referrer
        );
    }
}
