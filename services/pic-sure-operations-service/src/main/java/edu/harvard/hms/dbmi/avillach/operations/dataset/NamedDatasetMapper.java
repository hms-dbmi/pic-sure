package edu.harvard.hms.dbmi.avillach.operations.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.operations.query.Query;

/**
 * Translates between the {@code pic-sure-api-data} {@link NamedDataset} entity and this service's DTOs. Pure field mapping -- the
 * {@code Query} referenced by {@code queryId} is resolved/persisted by {@link NamedDatasetService} (via {@code QueryRepository}) and handed
 * in already-loaded, mirroring the legacy WAR's {@code NamedDatasetService}.
 */
@Component
public class NamedDatasetMapper {

    /**
     * Lenient mapper used ONLY to deserialize a stored v1 {@code query} node in {@link #convertQuery(Query)}: unknown fields on a stored row that
     * predate the current v1 {@code Query} model must not abort translation, so this mapper does not fail on
     * unknown properties. Never used for anything else in this class.
     */
    private static final ObjectMapper V1_QUERY_MAPPER =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public NamedDatasetDto toDto(NamedDataset e) {
        return new NamedDatasetDto(e.getUuid(), e.getUser(), e.getName(), toQueryDto(e.getQuery()), e.getArchived(), e.getMetadata());
    }

    /** {@code startTime} is converted to epoch millis here -- see {@link NamedDatasetQueryDto} for why the wire type is a number. */
    private NamedDatasetQueryDto toQueryDto(Query q) {
        if (q == null) {
            return null;
        }
        return new NamedDatasetQueryDto(
            q.getUuid(), convertQuery(q), q.getStartTime() == null ? null : q.getStartTime().getTime(), q.getStatus()
        );
    }

    private static String convertQuery(Query q) {
        if (q.getQuery() == null) {
            return null;
        }
        if (q.getQuery() == "") {
            return "";
        }
        if (isV3(q)) {
            return q.getQuery();
        }
        try {
            edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1 = V1_QUERY_MAPPER.readValue(q.getQuery(), edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
            edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query v3 = QueryTranslator.translate(v1);
            return V1_QUERY_MAPPER.writeValueAsString(v3);
        } catch (JsonProcessingException | UntranslatableQueryException e) {
            throw new RuntimeException(e);
        }
    }

    /** Preserves PicsureQueryService.isV3Query: version major == "3" (null-safe). */
    static boolean isV3(Query query) {
        String v = query.getVersion();
        return v != null && v.split("\\.")[0].equals("3");
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
