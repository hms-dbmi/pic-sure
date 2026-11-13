package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.StoredQueryConsentStripper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.StoredQueryBody;

/**
 * Translates between the {@code pic-sure-api-data} {@link NamedDataset} entity and this service's DTOs. Pure field mapping -- the
 * {@code Query} referenced by {@code queryId} is resolved and persisted by {@link NamedDatasetService} through {@code QueryRepository},
 * then handed to this mapper already loaded.
 */
@Component
public class NamedDatasetMapper {

    private static final ObjectMapper QUERY_MAPPER =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Logger log = LogManager.getLogger(NamedDatasetMapper.class);

    /**
     * Builds the public shape of a named dataset.
     *
     * @param e the dataset, with the query its {@code queryId} names already loaded
     * @param queries the variants of the dataset's general query to list, in the order they are to be returned
     * @return the dataset with its nested query and the listed variants
     * @throws PicsureException 500 when the stored body of the nested query cannot be converted to v3
     */
    public NamedDatasetDto toDto(NamedDataset e, List<LinkedQueryDto> queries) {
        return new NamedDatasetDto(
            e.getUuid(), e.getUser(), e.getName(), toQueryDto(e.getQuery()), e.getArchived(), e.getMetadata(), List.copyOf(queries)
        );
    }

    /** {@code startTime} is converted to epoch millis here -- see {@link NamedDatasetQueryDto} for why the wire type is a number. */
    private NamedDatasetQueryDto toQueryDto(Query q) {
        if (q == null) {
            return null;
        }
        return new NamedDatasetQueryDto(
            q.getUuid(), convertQuery(q), q.getStartTime() == null ? null : q.getStartTime().toEpochMilli(), q.getStatus()
        );
    }

    /**
     * Returns a v3 request wrapper without changing the stored query. The wrapper carries no consent value: it goes through
     * {@link StoredQueryConsentStripper} after the conversion, so a query stored before v3 loses the consent filters its translation
     * produced. Every other member of the stored body is returned as it is.
     */
    private static String convertQuery(Query q) {
        String stored = q.getQuery();
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            ObjectNode wrapper = StoredQueryBody.v3Request(q, stored);
            StoredQueryConsentStripper.strip(wrapper);
            return QUERY_MAPPER.writeValueAsString(wrapper);
        } catch (JsonProcessingException | UntranslatableQueryException | IllegalArgumentException e) {
            log.warn("Unable to convert saved query {} to v3", q.getUuid());
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unable to convert saved query to v3");
        }
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
