package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserClaims;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The user-profile response is built from the identity claims an identity provider returned. Production logs at INFO, so those claims must
 * not be written at INFO or above. Development logs at DEBUG, where the full claims map is wanted.
 */
class UserServiceClaimsLoggingTest {

    private static final String SUBJECT_CANARY = "okta-ras|canary-subject-value";
    private static final String EMAIL_CANARY = "canary.person@example.org";
    private static final String USERNAME_CANARY = "canary-preferred-username";
    private static final String MAPPING_CANARY = "canary-user-mapping-id";

    private ch.qos.logback.classic.Logger userServiceLogger;
    private Level originalLogLevel;
    private ListAppender<ILoggingEvent> logAppender;
    private UserService userService;

    @BeforeEach
    void setUp() {
        TOSService tosService = mock(TOSService.class);
        userService = new UserService(
            mock(BasicMailService.class), tosService, mock(UserRepository.class), mock(ConnectionRepository.class), mock(RoleService.class),
            mock(UserConsentsRepository.class), mock(FenceMappingUtility.class), 3600000L, 2592000000L, mock(JWTUtil.class),
            "ADMIN,SUPER_ADMIN", mock(LoggingClient.class)
        );

        userServiceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UserService.class);
        originalLogLevel = userServiceLogger.getLevel();
        userServiceLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        userServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        userServiceLogger.detachAppender(logAppender);
        userServiceLogger.setLevel(originalLogLevel);
    }

    @Test
    void buildingTheProfileResponseLogsIdentityClaimValuesOnlyAtDebug() {
        UserClaims claims = new UserClaims();
        claims.setSub(SUBJECT_CANARY);
        claims.setEmail(EMAIL_CANARY);
        claims.setPreferred_username(USERNAME_CANARY);
        claims.setUser_mapping_id(MAPPING_CANARY);
        claims.setUuid("11111111-2222-3333-4444-555555555555");

        assertNotNull(userService.getUserProfileResponse(claims));

        for (String canary : List.of(SUBJECT_CANARY, EMAIL_CANARY, USERNAME_CANARY, MAPPING_CANARY)) {
            for (ILoggingEvent event : logAppender.list) {
                if (!event.getLevel().isGreaterOrEqual(Level.INFO)) {
                    continue;
                }
                assertFalse(
                    event.getFormattedMessage().contains(canary),
                    "a " + event.getLevel() + " log line carries an identity claim: " + event.getFormattedMessage()
                );
            }
        }
        assertTrue(
            logAppender.list.stream().filter(event -> event.getLevel() == Level.DEBUG).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(EMAIL_CANARY)),
            "the DEBUG claims line should carry the full claims map"
        );
    }
}
