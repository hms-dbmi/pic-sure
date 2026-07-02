package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wraps an {@link HttpServletRequest} around an already-buffered {@code byte[]} body so downstream auth filters (introspection, consent
 * body swap) can read — and, via {@link #setBody}, replace — the request body, and so the body can be re-read (each
 * {@link #getInputStream()} call yields a fresh stream over the current bytes).
 */
public class BufferedRequestWrapper extends HttpServletRequestWrapper {

    private byte[] body;

    public BufferedRequestWrapper(HttpServletRequest request, byte[] initialBody) {
        super(request);
        this.body = initialBody == null ? new byte[0] : initialBody;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] newBody) {
        this.body = newBody == null ? new byte[0] : newBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {}
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
