package edu.harvard.hms.dbmi.avillach.auth.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * An expired PSAMA application token stops every introspection call the gateway makes. The log line PSAMA writes for it has to say which
 * token expired and how to replace it, not just that a JWT failed to parse.
 */
class JWTFilterExpiredApplicationTokenTest {

    private static final String SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";
    private static final String APPLICATION_SUBJECT = AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|app-uuid";
    private static final String INSPECT_URI = "/auth/token/inspect";

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(JWTFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private CustomUserDetailService userDetailsService;
    private JWTFilter filter;
    private FilterChain filterChain;
    private MockHttpServletRequest lastRequest;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        appender.start();
        filterLogger.addAppender(appender);
        userDetailsService = mock(CustomUserDetailService.class);
        filter = new JWTFilter(mock(TOSService.class), "sub", new JWTUtil(SECRET, false), userDetailsService, mock(SessionService.class));
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredApplicationTokenLogsHowToReplaceIt() throws Exception {
        MockHttpServletResponse response = call(INSPECT_URI, expiredToken(APPLICATION_SUBJECT, SECRET));

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
        verifyNoInteractions(userDetailsService);
        assertEquals("application_token_expired", AuditAttributes.getMetadata(lastRequest).get("auth_failure_reason"));
        assertEquals("application_token_expired", response.getHeader(JWTFilter.APPLICATION_TOKEN_REJECTED_HEADER));

        String message = singleErrorMessage();
        assertTrue(message.contains(APPLICATION_SUBJECT), message);
        assertTrue(message.contains("expired"), message);
        assertTrue(message.contains("Configure PIC-SURE Token Introspection Token"), message);
        assertTrue(message.contains("TOKEN_INTROSPECTION_TOKEN"), message);
    }

    @Test
    void expiredUserTokenKeepsTheGenericParseFailure() throws Exception {
        assertGenericParseFailure(call(INSPECT_URI, expiredToken("researcher-subject", SECRET)));
    }

    /**
     * Only a token whose signature checks out may name the application in the log. One signed with another key gets the generic failure.
     */
    @Test
    void expiredApplicationTokenWithAForeignSignatureKeepsTheGenericParseFailure() throws Exception {
        assertGenericParseFailure(call(INSPECT_URI, expiredToken(APPLICATION_SUBJECT, "another-secret-also-long-enough-for-hmac-sha-256")));
    }

    /**
     * An application token presented anywhere but the introspection endpoints is a misuse, not the gateway's credential expiring, so it
     * must not be reported with the introspection-token fix.
     */
    @Test
    void expiredApplicationTokenOnAnotherEndpointKeepsTheGenericParseFailure() throws Exception {
        assertGenericParseFailure(call("/auth/user", expiredToken(APPLICATION_SUBJECT, SECRET)));
    }

    private void assertGenericParseFailure(MockHttpServletResponse response) throws Exception {
        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
        assertEquals("Failed to parse JWT token.", singleErrorMessage());
        assertEquals("invalid_token", AuditAttributes.getMetadata(lastRequest).get("auth_failure_reason"));
        assertNull(response.getHeader(JWTFilter.APPLICATION_TOKEN_REJECTED_HEADER));
    }

    private String singleErrorMessage() {
        List<String> errors =
            appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(1, errors.size(), errors.toString());
        return errors.get(0);
    }

    private MockHttpServletResponse call(String uri, String token) throws Exception {
        lastRequest = new MockHttpServletRequest("POST", uri);
        lastRequest.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(lastRequest, response, filterChain);
        return response;
    }

    private static String expiredToken(String subject, String signingSecret) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(subject).issuedAt(new Date(now - 7_200_000)).expiration(new Date(now - 3_600_000))
            .signWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
