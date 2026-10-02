package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;

/**
 * {@code POST /open/validate} answers the bare boolean the authorization service decides, hands the service the gateway's body as the plain
 * map it always read, answers {@code false} without consulting it when open access is off, and no longer answers any other verb.
 */
class OpenAccessControllerTest {

    private static final String GATEWAY_BODY =
        "{\"request\":{\"Target Service\":\"/hpds/open/query/sync\"},\"ipAddress\":\"OPEN_ACCESS:localhost\","
            + "\"apiKey\":\"picsure_u_00000000000000000000000000000000000000000003tr27S\"}";

    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new OpenAccessController(authorizationService, FrozenWire.MAPPER, true));

    @Test
    void aPermittedRequestAnswersTrue() throws Exception {
        when(authorizationService.openAccessRequestIsValid(anyMap())).thenReturn(true);

        mockMvc.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(content().string("true"));
    }

    @Test
    void aDeniedRequestAnswersFalse() throws Exception {
        when(authorizationService.openAccessRequestIsValid(anyMap())).thenReturn(false);

        mockMvc.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    @Test
    void theServiceReceivesTheGatewayBodyAsAPlainMap() throws Exception {
        when(authorizationService.openAccessRequestIsValid(anyMap())).thenReturn(true);

        mockMvc.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.captor();
        verify(authorizationService).openAccessRequestIsValid(input.capture());
        assertThat(input.getValue()).isInstanceOf(HashMap.class).isEqualTo(
            Map.of(
                "request", Map.of("Target Service", "/hpds/open/query/sync"), "ipAddress", "OPEN_ACCESS:localhost", "apiKey",
                "picsure_u_00000000000000000000000000000000000000000003tr27S"
            )
        );
    }

    @Test
    void anEmptyBodyReachesTheServiceAsAnEmptyMap() throws Exception {
        when(authorizationService.openAccessRequestIsValid(anyMap())).thenReturn(true);

        mockMvc.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

        verify(authorizationService).openAccessRequestIsValid(Map.of());
    }

    @Test
    void openAccessOffAnswersFalseWithoutEvaluating() throws Exception {
        MockMvc off = FrozenWire.mockMvc(new OpenAccessController(authorizationService, FrozenWire.MAPPER, false));

        off.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andExpect(status().isOk())
            .andExpect(content().string("false"));

        verifyNoInteractions(authorizationService);
    }

    @Test
    void getIsNotAccepted() throws Exception {
        mockMvc.perform(get("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY))
            .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(authorizationService);
    }
}
