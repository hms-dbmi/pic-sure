package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/** Checks that the {@code sample} flag of a v3 {@link Query} defaults to false and survives every copy the record makes of itself. */
public class QuerySampleTest {

    private static final UUID PICSURE_ID = UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6");

    private static Query sampleQuery(UUID id) {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, PICSURE_ID, id, true);
    }

    @Test
    public void sampleDefaultsToFalseWhenJsonOmitsIt() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        Query omitted = mapper.readValue("{\"expectedResultType\":\"DATAFRAME\"}", Query.class);
        Query sent = mapper.readValue("{\"expectedResultType\":\"DATAFRAME\",\"sample\":true}", Query.class);

        assertFalse(omitted.sample());
        assertTrue(sent.sample());
    }

    @Test
    public void setAuthorizationFiltersKeepsSample() {
        List<AuthorizationFilter> filters = List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1")));

        Query copy = sampleQuery(null).setAuthorizationFilters(filters);

        assertTrue(copy.sample());
        assertEquals(filters, copy.authorizationFilters());
        assertEquals(PICSURE_ID, copy.picsureId());
    }

    @Test
    public void setPicsureIdReplacesTheIdAndKeepsEveryOtherComponent() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<AuthorizationFilter> filters = List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1")));
        List<GenomicFilter> genomic = List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null));
        PhenotypicClause clause = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\demographics\\SEX\\", null, null, null, null);
        Query query = new Query(List.of("\\demographics\\AGE\\"), filters, clause, genomic, ResultType.DATAFRAME, PICSURE_ID, id, true);

        assertEquals(
            new Query(List.of("\\demographics\\AGE\\"), filters, clause, genomic, ResultType.DATAFRAME, other, id, true),
            query.setPicsureId(other)
        );
        assertEquals(
            new Query(List.of("\\demographics\\AGE\\"), filters, clause, genomic, ResultType.DATAFRAME, null, id, true),
            query.setPicsureId(null)
        );
    }

    @Test
    public void aQueryWhosePicsureIdWasRemovedEqualsTheSameQueryReadWithoutOne() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Query sentWithout = mapper.readValue("{\"expectedResultType\":\"COUNT\"}", Query.class);
        Query sentWith = mapper.readValue("{\"expectedResultType\":\"COUNT\",\"picsureId\":\"" + PICSURE_ID + "\"}", Query.class);

        assertEquals(sentWithout, sentWith.setPicsureId(null));
        assertEquals(sentWithout.toString(), sentWith.setPicsureId(null).toString());
    }

    @Test
    public void generateIdKeepsSample() {
        Query copy = sampleQuery(null).generateId();

        assertNotNull(copy.id());
        assertTrue(copy.sample());
        assertEquals(PICSURE_ID, copy.picsureId());
    }

    @Test
    public void sevenArgumentConstructorSetsSampleFalse() {
        UUID id = UUID.randomUUID();

        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, PICSURE_ID, id);

        assertFalse(query.sample());
        assertEquals(new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, PICSURE_ID, id, false), query);
    }

    @Test
    public void sampleFalseIsOmittedFromJson() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        UUID id = UUID.fromString("0b0e2f0c-6a55-4f0e-9a3c-2f4a3a1d9c11");
        Query query = new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, PICSURE_ID, id);

        String written = mapper.writeValueAsString(query);

        assertFalse(mapper.readTree(written).has("sample"));
        assertEquals(
            "{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[],\"phenotypicClause\":null,\"genomicFilters\":[],"
                + "\"expectedResultType\":\"DATAFRAME\",\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\","
                + "\"id\":\"0b0e2f0c-6a55-4f0e-9a3c-2f4a3a1d9c11\"}",
            written
        );
    }

    @Test
    public void sampleTrueIsWrittenToJson() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode written = mapper.readTree(mapper.writeValueAsString(sampleQuery(null)));

        assertTrue(written.has("sample"));
        assertTrue(written.get("sample").isBoolean());
        assertTrue(written.get("sample").asBoolean());
    }

    @Test
    public void sampleTrueSurvivesSerializeAndDeserialize() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Query query = sampleQuery(UUID.randomUUID());

        Query read = mapper.readValue(mapper.writeValueAsString(query), Query.class);

        assertTrue(read.sample());
        assertEquals(query, read);
    }
}
