package edu.harvard.dbmi.avillach.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Pins the JSON of the records the operations service and the query service exchange over {@code /internal/queries}. The mapper is a plain
 * {@link ObjectMapper}, which fails on unknown properties, so a request record that binds a body with an extra member proves its own
 * {@code ignoreUnknown} setting and not a lenient mapper.
 */
public class InternalQueryModelsTest {

    private static final UUID ID = UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void savedQueryReferenceIsOnePicsureIdMember() throws JsonProcessingException {
        assertEquals("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}", mapper.writeValueAsString(new SavedQueryReference(ID)));
        assertEquals(
            new SavedQueryReference(ID),
            mapper.readValue("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}", SavedQueryReference.class)
        );
    }

    @Test
    public void dispatchResponseCarriesTheQueryAsAString() throws JsonProcessingException {
        assertEquals("{\"queryJson\":\"{\\\"query\\\":\\\"q\\\"}\"}", mapper.writeValueAsString(new DispatchResponse("{\"query\":\"q\"}")));
    }

    @Test
    public void dispatchResponseWritesAnExplicitNull() throws JsonProcessingException {
        assertEquals("{\"queryJson\":null}", mapper.writeValueAsString(new DispatchResponse(null)));
    }

    @Test
    public void storedQueryWritesEveryMemberInDeclarationOrderNullsIncluded() throws JsonProcessingException {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", null, 1790777100000L, null);

        assertEquals(
            "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{}\",\"resourceResultId\":\"rr-1\","
                + "\"status\":\"AVAILABLE\",\"version\":\"3\",\"metadata\":null,\"startTime\":1790777100000,\"readyTime\":null}",
            mapper.writeValueAsString(stored)
        );
    }

    @Test
    public void storedQueryBindsThroughItsCanonicalConstructor() throws JsonProcessingException {
        StoredQuery stored = mapper.readValue(
            "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{}\",\"resourceResultId\":\"rr-1\","
                + "\"status\":\"AVAILABLE\",\"version\":\"3\",\"metadata\":\"YWJj\",\"startTime\":1790777100000,\"readyTime\":1790777160000}",
            StoredQuery.class
        );

        assertEquals(new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", "YWJj", 1790777100000L, 1790777160000L), stored);
    }

    @Test
    public void storedQueryWithoutTimingLeavesBothTimestampsNull() {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", "YWJj");

        assertNull(stored.startTime());
        assertNull(stored.readyTime());
    }

    @Test
    public void saveQueryRequestWritesEveryMemberAndIgnoresUnknownOnes() throws JsonProcessingException {
        assertEquals(
            "{\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null}",
            mapper.writeValueAsString(new SaveQueryRequest("{}", null, "QUEUED", "3", null))
        );
        assertEquals(
            new SaveQueryRequest("{}", null, "QUEUED", null, null),
            mapper.readValue("{\"query\":\"{}\",\"status\":\"QUEUED\",\"resourceCredentials\":{}}", SaveQueryRequest.class)
        );
    }

    @Test
    public void updateQueryRequestWritesEveryMemberAndIgnoresUnknownOnes() throws JsonProcessingException {
        assertEquals(
            "{\"status\":\"AVAILABLE\",\"resourceResultId\":\"rr-1\",\"metadata\":null,\"query\":null,\"version\":null}",
            mapper.writeValueAsString(new UpdateQueryRequest("AVAILABLE", "rr-1", null, null, null))
        );
        assertEquals(
            new UpdateQueryRequest("AVAILABLE", null, null, null, null),
            mapper.readValue("{\"status\":\"AVAILABLE\",\"picsureId\":\"ignored\"}", UpdateQueryRequest.class)
        );
    }

    @Test
    public void everyRecordAndComponentIsDescribedAndCarriesAnExample() {
        List<String> problems = new ArrayList<>();
        List<Class<?>> records =
            List.of(SaveQueryRequest.class, UpdateQueryRequest.class, StoredQuery.class, SavedQueryReference.class, DispatchResponse.class);
        for (Class<?> type : records) {
            Schema onType = type.getAnnotation(Schema.class);
            if (onType == null || onType.description().isBlank()) {
                problems.add(type.getSimpleName() + " has no description");
            }
            for (RecordComponent component : type.getRecordComponents()) {
                String member = type.getSimpleName() + "." + component.getName();
                Schema onComponent = component.getAccessor().getAnnotation(Schema.class);
                if (onComponent == null || onComponent.description().isBlank()) {
                    problems.add(member + " has no description");
                }
                if (onComponent == null || onComponent.example().isBlank()) {
                    problems.add(member + " has no example");
                }
            }
        }

        assertEquals(List.of(), problems);
    }
}
