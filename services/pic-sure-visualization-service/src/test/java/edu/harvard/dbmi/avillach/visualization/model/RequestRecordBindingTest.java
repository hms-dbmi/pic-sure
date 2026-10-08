package edu.harvard.dbmi.avillach.visualization.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Both request records ignore properties they do not name, whatever the mapper's own setting. The plain {@link ObjectMapper} used here
 * rejects unknown properties by default, so these pass only because of the records' own annotation.
 */
class RequestRecordBindingTest {

    private final ObjectMapper strictMapper = new ObjectMapper();

    @Test
    void continuousBinningRequestIgnoresTheRestOfTheQueryEnvelope() throws Exception {
        String envelope =
            "{\"@type\":\"GeneralQueryRequest\",\"query\":{\"\\\\demographics\\\\AGE\\\\\":{\"45\":12}},\"resourceUUID\":null}";

        ContinuousBinningRequest request = strictMapper.readValue(envelope, ContinuousBinningRequest.class);

        assertThat(request.query()).isEqualTo(Map.of("\\demographics\\AGE\\", Map.of("45", 12)));
    }

    @Test
    void distributionRequestIgnoresTheRemovedResourceSelector() throws Exception {
        String body =
            "{\"hpdsResourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":{\"select\":[\"\\\\demographics\\\\SEX\\\\\"]}}";

        DistributionRequest request = strictMapper.readValue(body, DistributionRequest.class);

        assertThat(request.query().select()).containsExactly("\\demographics\\SEX\\");
    }
}
