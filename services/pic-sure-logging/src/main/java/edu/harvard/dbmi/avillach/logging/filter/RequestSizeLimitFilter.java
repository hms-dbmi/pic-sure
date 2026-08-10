package edu.harvard.dbmi.avillach.logging.filter;

import edu.harvard.dbmi.avillach.logging.web.RequestBodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Restores Javalin's 1 MB maxRequestSize. Spring has no equivalent for raw JSON bodies (server.tomcat.max-http-form-post-size is
 * form-only).
 *
 * <p>Runs outside DispatcherServlet, so it writes its own response body.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_REQUEST_BYTES = 1_048_576L;

    private static final String BODY = "{\"status\":\"error\",\"message\":\"Request body too large\"}";

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BODY);
            return;
        }

        if (declared >= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // Chunked transfer encoding: no declared length, so count bytes as they are read.
        filterChain.doFilter(new CountingRequestWrapper(request, maxBytes), response);
    }

    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;

        // Memoized so every caller shares one running count instead of each resetting to zero.
        private ServletInputStream countingStream;
        // Memoized for the same reason as countingStream: one shared running count.
        private BufferedReader reader;

        CountingRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (countingStream == null) {
                countingStream = newCountingStream(super.getInputStream(), maxBytes);
            }
            return countingStream;
        }

        /**
         * Routes character-based reads through the same counting stream, so the cap holds whichever of getInputStream()/getReader() a
         * consumer picks.
         */
        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }

        private static ServletInputStream newCountingStream(ServletInputStream delegate, long limit) {
            return new ServletInputStream() {

                private long count;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b != -1 && ++count > limit) {
                        throw new RequestBodyTooLargeException("Request body exceeds " + limit + " bytes");
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate.read(b, off, len);
                    if (n > 0 && (count += n) > limit) {
                        throw new RequestBodyTooLargeException("Request body exceeds " + limit + " bytes");
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
