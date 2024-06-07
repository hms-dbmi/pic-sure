package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class TOSServiceTest {

    @Mock
    private TermsOfServiceRepository termsOfServiceRepo;

    @Mock
    private UserRepository userRepo;

    private TOSService tosService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        tosService = new TOSService(termsOfServiceRepo, userRepo, true);
    }

    @Test
    public void testHasUserAcceptedLatest_TOSDisabled() {
        TOSService service = new TOSService(termsOfServiceRepo, userRepo, false);

        boolean result = service.hasUserAcceptedLatest("user-id");
        assertTrue(result);
    }

    @Test
    public void testHasUserAcceptedLatest_NoTOSFound() {
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.empty());

        boolean result = tosService.hasUserAcceptedLatest("user-id");
        assertTrue(result);
    }

    @Test
    public void testHasUserAcceptedLatest_UserNotAcceptedLatestTOS() {
        User user = new User();
        user.setAcceptedTOS(new Date(0)); // Set an old date

        TermsOfService latestTOS = new TermsOfService();
        latestTOS.setDateUpdated(new Date());
        latestTOS.setContent("Latest TOS content");

        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(latestTOS));

        boolean result = tosService.hasUserAcceptedLatest(UUID.randomUUID().toString());
        assertFalse(result);
    }

    @Test
    public void testHasUserAcceptedLatest_UserAcceptedLatestTOS() {
        User user = new User();
        user.setAcceptedTOS(new Date());

        TermsOfService latestTOS = new TermsOfService();
        latestTOS.setDateUpdated(new Date(0)); // Set an old date

        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(latestTOS));

        boolean result = tosService.hasUserAcceptedLatest(UUID.randomUUID().toString());
        assertTrue(result);
    }

    @Test
    public void testUpdateTermsOfService() {
        String html = "<p>New TOS content</p>";

        TermsOfService updatedTOS = new TermsOfService();
        updatedTOS.setContent(html);
        when(termsOfServiceRepo.save(any(TermsOfService.class))).thenReturn(updatedTOS);
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(updatedTOS));

        Optional<TermsOfService> result = tosService.updateTermsOfService(html);
        assertTrue(result.isPresent());
        assertEquals(html, result.get().getContent());
    }

    @Test
    public void testGetLatest_TOSFound() {
        String content = "<p>Latest TOS content</p>";

        TermsOfService tos = new TermsOfService();
        tos.setContent(content);
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(tos));

        String result = tosService.getLatest();
        assertEquals(content, result);
    }

    @Test
    public void testGetLatest_NoTOSFound() {
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.empty());

        String result = tosService.getLatest();
        assertNull(result);
    }

    @Test
    public void testAcceptTermsOfService_UserExists() {
        User user = new User();
        user.setSubject("user-id");

        TermsOfService tos = new TermsOfService();
        tos.setDateUpdated(new Date());

        when(userRepo.findBySubject(anyString())).thenReturn(user);
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(tos));

        User result = tosService.acceptTermsOfService("user-id");

        assertNotNull(result);
        assertNotNull(result.getAcceptedTOS());
    }

    @Test(expected = RuntimeException.class)
    public void testAcceptTermsOfService_UserDoesNotExist() {
        when(userRepo.findBySubject(anyString())).thenReturn(null);

        tosService.acceptTermsOfService("user-id");
    }

    @Test(expected = RuntimeException.class)
    public void testAcceptTermsOfService_NoTOSInDatabase() {
        User user = new User();
        user.setSubject("user-id");

        when(userRepo.findBySubject(anyString())).thenReturn(user);
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.empty());

        tosService.acceptTermsOfService("user-id");
    }

    @Test
    public void testCheckAgainstTOSDate_UserAcceptedLatestTOS() {
        User user = new User();
        user.setAcceptedTOS(new Date());

        TermsOfService tos = new TermsOfService();
        tos.setDateUpdated(new Date(0)); // Set an old date

        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(tos));

        boolean result = tosService.hasUserAcceptedLatest(UUID.randomUUID().toString());
        assertTrue(result);
    }

    @Test
    public void testCheckAgainstTOSDate_UserNotAcceptedLatestTOS() {
        User user = new User();
        user.setAcceptedTOS(new Date(0));

        TermsOfService tos = new TermsOfService();
        tos.setDateUpdated(new Date());
        tos.setContent("Latest TOS content");

        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(tos));

        boolean result = tosService.hasUserAcceptedLatest(UUID.randomUUID().toString());
        assertFalse(result);
    }

    @Test
    public void testCheckAgainstTOSDate_UserNotFound() {
        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.empty());

        TermsOfService tos = new TermsOfService();
        tos.setDateUpdated(new Date());
        tos.setContent("Latest TOS content");

        when(termsOfServiceRepo.findTopByOrderByDateUpdatedDesc()).thenReturn(Optional.of(tos));

        boolean result = tosService.hasUserAcceptedLatest(UUID.randomUUID().toString());
        assertFalse(result);
    }

}