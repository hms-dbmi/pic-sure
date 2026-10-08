package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins what the legacy search accepts when Jackson binds its body to a record. A body with a string term and a positive limit becomes a
 * search with the matching text search query and page size. A body that is not JSON, has no query object, or has a missing or non-positive
 * limit answers 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegacySearchBindingTest {

    private static final String LEGACY_CLIENT_BODY =
        "{\"query\":{\"searchTerm\":\"age\",\"includedTags\":[],\"excludedTags\":[],\"returnTags\":\"true\",\"offset\":0,\"limit\":100}}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LegacySearchService legacySearchService;

    @BeforeEach
    void answerWithNoResults() {
        when(legacySearchService.getSearchResults(any(Filter.class), any(Pageable.class))).thenReturn(new Results(List.of()));
    }

    @Test
    void bindsTheBodyTheLegacyClientsSend() throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content(LEGACY_CLIENT_BODY)).andExpect(status().isOk())
            .andExpect(content().json("{\"results\":{\"searchResults\":[]}}", true));

        assertSearched("age:*", 100);
    }

    @Test
    void stillAnswersWithoutAnHttpMethodRestriction() throws Exception {
        mockMvc.perform(get("/search").contentType(MediaType.APPLICATION_JSON).content(LEGACY_CLIENT_BODY)).andExpect(status().isOk());

        assertSearched("age:*", 100);
    }

    @ParameterizedTest(name = "{0} searches [{1}] with page size {2}")
    @CsvSource(
        delimiter = ';',
        value = {"{\"query\":{\"searchTerm\":\"sex|gender age\",\"limit\":100}};sex:* | gender:* & age:*;100",
            "{\"query\":{\"searchTerm\":\"age\",\"limit\":\"100\"}};age:*;100",
            "{\"query\":{\"searchTerm\":\"age\",\"limit\":10.7}};age:*;10", "{\"query\":{\"searchTerm\":5,\"limit\":10}};5:*;10",
            "{\"query\":{\"searchTerm\":true,\"limit\":10}};true:*;10", "{\"query\":{\"searchTerm\":\"\",\"limit\":10}};'';10",
            "{\"query\":{\"searchTerm\":null,\"limit\":10}};'';10", "{\"query\":{\"limit\":10}};'';10",
            "{\"query\":{\"searchTerm\":\"age\",\"limit\":10},\"resourceUUID\":\"x\"};age:*;10"}
    )
    void bindsWhatJacksonCoerces(String body, String search, int pageSize) throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        assertSearched(search, pageSize);
    }

    @ParameterizedTest(name = "{0} is a 400")
    @ValueSource(
        strings = {"{}", "{\"query\":null}", "{\"query\":\"age\"}", "{\"query\":{\"searchTerm\":\"age\"}}",
            "{\"query\":{\"searchTerm\":\"age\",\"limit\":null}}", "{\"query\":{\"searchTerm\":\"age\",\"limit\":0}}",
            "{\"query\":{\"searchTerm\":\"age\",\"limit\":\"abc\"}}", "{\"query\":{\"searchTerm\":\"age\",\"limit\":true}}",
            "{\"query\":{\"searchTerm\":{\"a\":1},\"limit\":10}}", "{\"query\":{\"searchTerm\":[\"a\"],\"limit\":10}}", "[]", "{\"query\":",
            " "}
    )
    void rejectsWhatCannotBeSearched(String body) throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
            .andExpect(content().string(""));

        verifyNoInteractions(legacySearchService);
    }

    @Test
    void rejectsABodyThatIsNotDeclaredJson() throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.TEXT_PLAIN).content(LEGACY_CLIENT_BODY))
            .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(legacySearchService);
    }

    private void assertSearched(String search, int pageSize) {
        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(legacySearchService).getSearchResults(filter.capture(), pageable.capture());
        assertThat(filter.getValue()).isEqualTo(new Filter(List.of(), search, List.of()));
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(pageSize);
    }
}
