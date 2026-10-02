package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.domain.DispatchResponse;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;

/**
 * Holds the two internal query producers to the exact bytes they emit and to the shared records they declare. The three wire tests assert
 * whole response bodies, so they pass against a handler that builds a map and against one that returns a record only when both write the
 * same JSON. The signature test is what requires the records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalQueryWireTest {

    private static final Pattern SAVED_BODY =
        Pattern.compile("^\\{\"picsureId\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"}$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueryRepository queryRepo;

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    @Test
    void saveAnswers201WithOnePicsureIdMember() throws Exception {
        String body = mockMvc
            .perform(
                post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\"}")
            ).andExpect(status().isCreated()).andExpect(content().contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse()
            .getContentAsString();

        Matcher saved = SAVED_BODY.matcher(body);
        assertThat(saved.matches()).as("save body %s", body).isTrue();
        assertThat(queryRepo.findById(UUID.fromString(saved.group(1)))).isPresent();
    }

    @Test
    void dispatchAnswersWithTheStoredQueryAsOneJsonString() throws Exception {
        Query saved = new Query();
        saved.setQuery("{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}");
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"queryJson\":\"{\\\"resourceUUID\\\":\\\"r\\\",\\\"query\\\":\\\"q\\\"}\"}"));
    }

    @Test
    void dispatchOfARowWithNoQueryAnswersWithAnExplicitNull() throws Exception {
        Query saved = queryRepo.save(new Query());

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"queryJson\":null}"));
    }

    @Test
    void handlersDeclareTheSharedRecords() throws Exception {
        Method saveHandler = InternalQueryController.class.getMethod("save", SaveQueryRequest.class);
        Method readHandler = InternalQueryController.class.getMethod("get", UUID.class);
        Method updateHandler = InternalQueryController.class.getMethod("update", UUID.class, UpdateQueryRequest.class);
        Method dispatchHandler = InternalQueryController.class.getMethod("dispatch", UUID.class);

        assertThat(saveHandler.getGenericReturnType().getTypeName())
            .isEqualTo("org.springframework.http.ResponseEntity<edu.harvard.dbmi.avillach.domain.SavedQueryReference>");
        assertThat(readHandler.getReturnType()).isEqualTo(StoredQuery.class);
        assertThat(updateHandler.getGenericReturnType().getTypeName()).isEqualTo("org.springframework.http.ResponseEntity<java.lang.Void>");
        assertThat(dispatchHandler.getReturnType()).isEqualTo(DispatchResponse.class);
    }
}
