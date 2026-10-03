package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptPage;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves that {@link ConceptPage} writes what Spring Data's {@code PageImpl} writes for the same page. Both are serialized with the
 * service's own {@link ObjectMapper} for a full page, a last page and an empty page.
 *
 * <p> {@code PageImpl} has no stable key order: Jackson reads its getters in reflection order, which changes from one JVM start to the
 * next, so the same page serializes with {@code totalElements} before {@code totalPages} in one run and after it in another. The comparison
 * is therefore in two parts. The parsed trees must be equal, which covers every key and value at every level whatever the order. The raw
 * text of {@code content} must be equal character for character, because the concepts are records with a fixed order and a repeated
 * {@code type} key that a parsed tree would hide.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConceptPageTest {

    private static final Concept SEX = new CategoricalConcept(
        "\\demographics\\SEX\\", "SEX", "Sex", "phs000007", "Sex of the participant", List.of("Male", "Female"), true, "FHS",
        List.of(
            new ContinuousConcept(
                "\\demographics\\SEX\\AGE\\", "AGE", "Age", "phs000007", "Age of the participant", true, 18D, 89D, "FHS", null
            )
        ), Map.of("Study Id", "phs000007"), new CategoricalConcept("\\demographics\\", "phs000007"),
        new Dataset("phs000007", "Framingham Cohort", "FHS", "The Framingham Heart Study")
    );
    private static final Concept AGE = new ContinuousConcept(
        "\\demographics\\AGE\\", "AGE", "Age", "phs000007", "Age of the participant", true, 18D, 89D, "FHS", Map.of()
    );
    private static final String EMPTY_PAGE =
        "{\"content\":[],\"pageable\":{\"pageNumber\":0,\"pageSize\":10,\"sort\":{\"unsorted\":true,\"sorted\":false,\"empty\":true},"
            + "\"offset\":0,\"paged\":true,\"unpaged\":false},\"totalElements\":0,\"totalPages\":0,\"last\":true,\"numberOfElements\":0,"
            + "\"sort\":{\"unsorted\":true,\"sorted\":false,\"empty\":true},\"first\":true,\"size\":10,\"number\":0,\"empty\":true}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ConceptService conceptService;

    @Test
    void fullPageMatchesPageImpl() throws Exception {
        assertSameJson(new PageImpl<>(List.of(SEX, AGE), PageRequest.of(0, 2), 25));
    }

    @Test
    void lastPageMatchesPageImpl() throws Exception {
        assertSameJson(new PageImpl<>(List.of(AGE), PageRequest.of(12, 2), 25));
    }

    @Test
    void emptyPageMatchesPageImpl() throws Exception {
        assertSameJson(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
    }

    @Test
    void keyOrderIsFixed() throws Exception {
        PageImpl<Concept> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        assertThat(objectMapper.writeValueAsString(ConceptPage.from(empty))).isEqualTo(EMPTY_PAGE);
    }

    @Test
    void contentConceptsCarryTypeOnceAndNestedConceptsTwice() throws Exception {
        String content =
            rawContent(objectMapper.writeValueAsString(ConceptPage.from(new PageImpl<>(List.of(SEX), PageRequest.of(0, 2), 1))));

        assertThat(content).startsWith("[{\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\"").endsWith("\"type\":\"Categorical\"}]");
        assertThat(content).contains("\"children\":[{\"type\":\"Continuous\",\"conceptPath\"");
        assertThat(content).contains("\"table\":{\"type\":\"Categorical\",\"conceptPath\"");
    }

    @Test
    void conceptSearchWritesThePage() throws Exception {
        PageRequest firstPage = PageRequest.of(0, 2);
        when(conceptService.countConcepts(any(Filter.class))).thenReturn(25L);
        when(conceptService.listConcepts(any(Filter.class), any(Pageable.class))).thenReturn(List.of(SEX, AGE));

        String body = mockMvc
            .perform(post("/concepts?page_number=0&page_size=2").contentType(MediaType.APPLICATION_JSON).content("{\"search\":\"age\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        PageImpl<Concept> expected = new PageImpl<>(List.of(SEX, AGE), firstPage, 25);
        assertThat(body).isEqualTo(objectMapper.writeValueAsString(ConceptPage.from(expected)));
        assertThat(objectMapper.readTree(body)).isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(expected)));
    }

    @Test
    void conceptDumpWritesThePage() throws Exception {
        when(conceptService.countConcepts(any(Filter.class))).thenReturn(1L);
        when(conceptService.listDetailedConcepts(any(Filter.class), any(Pageable.class))).thenReturn(List.of(SEX));

        String body = mockMvc.perform(get("/concepts/dump")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        PageImpl<Concept> expected = new PageImpl<>(List.of(SEX), PageRequest.of(0, 10), 1);
        assertThat(body).isEqualTo(objectMapper.writeValueAsString(ConceptPage.from(expected)));
        assertThat(objectMapper.readTree(body)).isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(expected)));
    }

    private void assertSameJson(PageImpl<Concept> page) throws Exception {
        String fromPageImpl = objectMapper.writeValueAsString(page);
        String fromRecord = objectMapper.writeValueAsString(ConceptPage.from(page));

        assertThat(objectMapper.readTree(fromRecord)).isEqualTo(objectMapper.readTree(fromPageImpl));
        assertThat(rawContent(fromRecord)).isEqualTo(rawContent(fromPageImpl));
    }

    private String rawContent(String json) throws Exception {
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                int start = (int) parser.currentTokenLocation().getCharOffset();
                parser.skipChildren();
                int end = (int) parser.currentLocation().getCharOffset();
                if (name.equals("content")) {
                    return json.substring(start, end);
                }
            }
        }
        throw new AssertionError("no content key in " + json);
    }
}
