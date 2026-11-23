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
 * Pins that a trailing comma in {@code metadata.internal_export_keys} does not put the empty string on the allowlist of
 * {@code POST /internal/concepts/export-metadata}. A request for the empty key answers 400 like any other key outside the list and never
 * reaches the repository, and the key that is listed still passes.
 */
@SpringBootTest(
    properties = {"picsure.internal.token=" + InternalConceptExportBlankKeyTest.TOKEN, "metadata.internal_export_keys=drs_uri,"}
)
@AutoConfigureMockMvc
class InternalConceptExportBlankKeyTest {

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
    void rejectsTheEmptyMetaKey() throws Exception {
        mockMvc.perform(export("{\"conceptPaths\":[\"a\"],\"metaKeys\":[\"\"]}")).andExpect(status().isBadRequest());

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void acceptsTheListedMetaKey() throws Exception {
        when(conceptRepository.getExportMetadata(List.of("a"), List.of("drs_uri"))).thenReturn(List.of());

        mockMvc.perform(export("{\"conceptPaths\":[\"a\"],\"metaKeys\":[\"drs_uri\"]}")).andExpect(status().isOk());

        verify(conceptRepository).getExportMetadata(List.of("a"), List.of("drs_uri"));
    }
}
