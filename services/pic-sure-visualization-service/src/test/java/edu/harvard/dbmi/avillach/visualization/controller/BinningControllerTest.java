package edu.harvard.dbmi.avillach.visualization.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the body {@code POST /bin/continuous} answers with: the binned counts wrapped under {@code bins}, concepts in request order and bins
 * in ascending order. The request is the exact envelope the query service's {@code AggregateBackendClient} sends.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BinningControllerTest {

    private static final String AGGREGATE_ENVELOPE = "{\"@type\":\"GeneralQueryRequest\",\"query\":{"
        + "\"\\\\demographics\\\\AGE\\\\\":{\"20\":5,\"30\":12,\"40\":25,\"50\":40,\"60\":31,\"70\":18,\"80\":6},"
        + "\"\\\\measurements\\\\bmi\\\\\":{\"27.5\":9}},\"resourceUUID\":null}";

    private static final String WRAPPED_BINS =
        "{\"bins\":{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 50.0\":42,\"50.0 +\":95},\"\\\\measurements\\\\bmi\\\\\":{\"27.5\":9}}}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void binContinuousWrapsTheBinsInTheResponseRecord() throws Exception {
        assertThat(bin("/bin/continuous")).isEqualTo(WRAPPED_BINS);
    }

    private String bin(String path) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(AGGREGATE_ENVELOPE)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
