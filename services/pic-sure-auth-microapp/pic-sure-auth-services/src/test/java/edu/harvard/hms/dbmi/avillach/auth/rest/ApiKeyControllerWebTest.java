package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests: exercise real JSON deserialization and request-parameter conversion through the {@link GlobalExceptionHandler}
 * advice, which plain controller unit tests bypass.
 */
public class ApiKeyControllerWebTest {

    private MockMvc mockMvc;
    private ApiKeyService apiKeyService;

    @BeforeEach
    public void setUp() {
        apiKeyService = Mockito.mock(ApiKeyService.class);
        ApiKeyController controller = new ApiKeyController(apiKeyService, (token, ip) -> true, true, true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    public void testMalformedJsonBodyReturns400WithoutParserDetails() throws Exception {
        MvcResult result = mockMvc.perform(post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
            .andExpect(status().isBadRequest()).andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("fasterxml"));
    }

    @Test
    public void testMissingBodyReturns400() throws Exception {
        mockMvc.perform(post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    }

    @Test
    public void testUnparseableExpiresAtReturns400() throws Exception {
        mockMvc.perform(
            post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Partner\",\"email\":\"a@b.com\",\"expiresAt\":\"not-a-date\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    public void testInvalidKeyTypeParamReturns400WithoutEchoingValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/apiKey").param("keyType", "bogus-type")).andExpect(status().isBadRequest()).andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("bogus-type"));
    }

    @Test
    public void testValidKeyTypeParamBindsCaseInsensitively() throws Exception {
        when(apiKeyService.listKeys(anyInt(), anyInt(), eq(ApiKeyType.PLATFORM))).thenReturn(new ApiKeyPage(List.of(), 0, 0, 100));

        mockMvc.perform(get("/apiKey").param("keyType", "PLATFORM")).andExpect(status().isOk());
    }
}
