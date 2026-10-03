package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;

/**
 * The terms of service endpoints answer the HTML text of the current terms, the stored row's JSON after an update (or an empty 200 when the
 * store cannot read it back), and an empty 200 after acceptance. The handlers read the caller from the security context, so each test signs
 * a user in. The MockMvc carries the string converter ahead of the JSON one, as the application does, so the HTML request and response
 * bodies are read and written as text. {@code GET /tos} is not exercised: it answers 500 on every tree, because no converter writes a
 * {@code Boolean} as {@code text/plain}.
 */
class TermsOfServiceControllerTest {

    private static final String HTML = "<h1>Terms of Service</h1><p>Use of this system is monitored.</p>";

    private final TOSService tosService = mock(TOSService.class);
    private final UserService userService = mock(UserService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TermsOfServiceController(tosService, userService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(
            new StringHttpMessageConverter(StandardCharsets.UTF_8), new MappingJackson2HttpMessageConverter(FrozenWire.MAPPER)
        ).build();
    private final User caller = new User().setSubject("fence|12345");

    @BeforeEach
    void signIn() {
        caller.setUuid(UUID.randomUUID());
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(new CustomUserDetails(caller), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void latestTermsAreTheHtmlText() throws Exception {
        when(tosService.getLatest()).thenReturn(HTML);

        mockMvc.perform(get("/tos/latest")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(HTML));
    }

    @Test
    void latestTermsAreEmptyWhenNoneAreStored() throws Exception {
        when(tosService.getLatest()).thenReturn(null);

        mockMvc.perform(get("/tos/latest")).andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void updatingTheTermsReturnsTheStoredRowJson() throws Exception {
        TermsOfService stored = new TermsOfService().setContent(HTML).setDateUpdated(new Date(1790777100000L));
        stored.setUuid(UUID.randomUUID());
        when(tosService.updateTermsOfService(HTML)).thenReturn(Optional.of(stored));
        when(tosService.acceptTermsOfService("fence|12345")).thenReturn(caller);
        when(userService.updateUser(anyList())).thenReturn(List.of(caller));

        mockMvc.perform(post("/tos/update").contentType(MediaType.TEXT_HTML).content(HTML)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(stored)));
    }

    @Test
    void updatingTheTermsWithNothingToReadBackIsAnEmpty200() throws Exception {
        when(tosService.updateTermsOfService(HTML)).thenReturn(Optional.empty());

        mockMvc.perform(post("/tos/update").contentType(MediaType.TEXT_HTML).content(HTML)).andExpect(status().isOk())
            .andExpect(content().string(""));
    }

    @Test
    void acceptingTheTermsIsAnEmpty200() throws Exception {
        when(tosService.acceptTermsOfService("fence|12345")).thenReturn(caller);
        when(userService.updateUser(anyList())).thenReturn(List.of(caller));

        mockMvc.perform(post("/tos/accept")).andExpect(status().isOk()).andExpect(content().string(""));
    }
}
