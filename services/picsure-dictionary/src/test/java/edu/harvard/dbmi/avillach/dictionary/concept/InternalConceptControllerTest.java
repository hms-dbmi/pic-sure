package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportMetadata;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportRequest;
import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * Pins what {@code POST /internal/concepts/export-metadata} accepts once the token gate lets a request through, with the real
 * {@link ConceptService} in front of a mocked {@link ConceptRepository}. A valid body reaches the repository and its rows come back as they
 * are. A body that asks for a metadata key outside {@code metadata.internal_export_keys}, sends more than 5000 paths, or leaves a list out
 * answers 400 and never reaches the repository.
 */
@SpringBootTest(properties = {"picsure.internal.token=" + InternalConceptControllerTest.TOKEN, "metadata.internal_export_keys=drs_uri,dcc"})
@AutoConfigureMockMvc
class InternalConceptControllerTest {

    static final String TOKEN = "inbound-secret";

    private static final String URL = "/internal/concepts/export-metadata";
    private static final String PATH = "\\phs000007\\pht000021\\phv00003844\\FL200\\";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ConceptRepository conceptRepository;

    private MockHttpServletRequestBuilder export(String body) {
        return post(URL).header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<String> paths(int count) {
        return IntStream.range(0, count).mapToObj(index -> "\\study\\concept" + index + "\\").toList();
    }

    @Test
    void returnsTheRowsTheRepositoryFinds() throws Exception {
        ConceptExportMetadata row =
            new ConceptExportMetadata(PATH, "FL200", "phs000007", "cola per day", Map.of("drs_uri", "[\"drs://dg.4503:dg.4503/aaa\"]"));
        when(conceptRepository.getExportMetadata(List.of(PATH), List.of("drs_uri"))).thenReturn(List.of(row));

        mockMvc.perform(export(objectMapper.writeValueAsString(new ConceptExportRequest(List.of(PATH), List.of("drs_uri")))))
            .andExpect(status().isOk()).andExpect(content().json(objectMapper.writeValueAsString(List.of(row)), true));
    }

    @Test
    void acceptsEveryConfiguredMetaKey() throws Exception {
        when(conceptRepository.getExportMetadata(List.of(PATH), List.of("dcc", "drs_uri"))).thenReturn(List.of());

        mockMvc.perform(export(objectMapper.writeValueAsString(new ConceptExportRequest(List.of(PATH), List.of("dcc", "drs_uri")))))
            .andExpect(status().isOk()).andExpect(content().json("[]", true));

        verify(conceptRepository).getExportMetadata(List.of(PATH), List.of("dcc", "drs_uri"));
    }

    @Test
    void acceptsFiveThousandPaths() throws Exception {
        List<String> paths = paths(5000);
        when(conceptRepository.getExportMetadata(paths, List.of("drs_uri"))).thenReturn(List.of());

        mockMvc.perform(export(objectMapper.writeValueAsString(new ConceptExportRequest(paths, List.of("drs_uri")))))
            .andExpect(status().isOk());

        verify(conceptRepository).getExportMetadata(paths, List.of("drs_uri"));
    }

    @Test
    void rejectsMoreThanFiveThousandPaths() throws Exception {
        mockMvc.perform(export(objectMapper.writeValueAsString(new ConceptExportRequest(paths(5001), List.of("drs_uri")))))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(conceptRepository);
    }

    @ParameterizedTest(name = "meta keys {0} are a 400")
    @ValueSource(strings = {"[\"stigmatized\"]", "[\"drs_uri\",\"values\"]", "[\"Drs Uri\"]", "[\"\"]", "[null]"})
    void rejectsAMetaKeyOutsideTheAllowlist(String metaKeys) throws Exception {
        mockMvc.perform(export("{\"conceptPaths\":[\"\\\\study\\\\concept\\\\\"],\"metaKeys\":" + metaKeys + "}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(conceptRepository);
    }

    @ParameterizedTest(name = "{0} is a 400")
    @ValueSource(
        strings = {"{}", "{\"conceptPaths\":null,\"metaKeys\":[\"drs_uri\"]}", "{\"conceptPaths\":[\"a\"]}",
            "{\"conceptPaths\":[\"a\"],\"metaKeys\":null}", "{\"conceptPaths\":[null],\"metaKeys\":[\"drs_uri\"]}",
            "{\"conceptPaths\":\"a\",\"metaKeys\":[\"drs_uri\"]}", "[]", " "}
    )
    void rejectsABodyWithoutBothLists(String body) throws Exception {
        mockMvc.perform(export(body)).andExpect(status().isBadRequest());

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void anEmptyPathListReturnsAnEmptyListWithoutAQuery() throws Exception {
        mockMvc.perform(export("{\"conceptPaths\":[],\"metaKeys\":[\"drs_uri\"]}")).andExpect(status().isOk())
            .andExpect(content().json("[]", true));

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void anEmptyMetaKeyListAsksTheRepositoryForNoKeys() throws Exception {
        when(conceptRepository.getExportMetadata(any(), any())).thenReturn(List.of());

        mockMvc.perform(export("{\"conceptPaths\":[\"a\"],\"metaKeys\":[]}")).andExpect(status().isOk());

        verify(conceptRepository).getExportMetadata(List.of("a"), List.of());
    }
}
