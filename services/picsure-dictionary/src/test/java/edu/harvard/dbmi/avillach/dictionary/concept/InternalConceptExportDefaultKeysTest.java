package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * Pins what {@code POST /internal/concepts/export-metadata} allows when a deployment leaves {@code metadata.internal_export_keys} at its
 * default. The default allowlist holds only {@code drs_uri}, so a request for it succeeds and a request for any other key, such as
 * {@code dcc}, answers 400 without reaching the repository.
 */
@SpringBootTest(properties = "picsure.internal.token=" + InternalConceptExportDefaultKeysTest.TOKEN)
@AutoConfigureMockMvc
class InternalConceptExportDefaultKeysTest {

    static final String TOKEN = "inbound-secret";

    private static final String URL = "/internal/concepts/export-metadata";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptRepository conceptRepository;

    private MockHttpServletRequestBuilder export(String body) {
        return post(URL).header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void acceptsDrsUri() throws Exception {
        when(conceptRepository.getExportMetadata(List.of("a"), List.of("drs_uri"))).thenReturn(List.of());

        mockMvc.perform(export("{\"conceptPaths\":[\"a\"],\"metaKeys\":[\"drs_uri\"]}")).andExpect(status().isOk());

        verify(conceptRepository).getExportMetadata(List.of("a"), List.of("drs_uri"));
    }

    @Test
    void rejectsAKeyOutsideTheDefault() throws Exception {
        mockMvc.perform(export("{\"conceptPaths\":[\"a\"],\"metaKeys\":[\"dcc\"]}")).andExpect(status().isBadRequest());

        verifyNoInteractions(conceptRepository);
    }
}
