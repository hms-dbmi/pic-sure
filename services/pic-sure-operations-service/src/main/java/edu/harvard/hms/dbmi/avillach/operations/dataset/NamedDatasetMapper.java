package edu.harvard.hms.dbmi.avillach.operations.dataset;

import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.operations.query.Query;

/**
 * Translates between the {@code pic-sure-api-data} {@link NamedDataset} entity and this service's DTOs. Pure field mapping -- the
 * {@code Query} referenced by {@code queryId} is resolved/persisted by {@link NamedDatasetService} (via {@code QueryRepository}) and handed
 * in already-loaded, mirroring the legacy WAR's {@code NamedDatasetService}.
 */
@Component
public class NamedDatasetMapper {

    public NamedDatasetDto toDto(NamedDataset e) {
        return new NamedDatasetDto(e.getUuid(), e.getUser(), e.getName(), toQueryDto(e.getQuery()), e.getArchived(), e.getMetadata());
    }

    /** {@code startTime} is converted to epoch millis here -- see {@link NamedDatasetQueryDto} for why the wire type is a number. */
    private NamedDatasetQueryDto toQueryDto(Query q) {
        if (q == null) {
            return null;
        }
        return new NamedDatasetQueryDto(
            q.getUuid(), q.getQuery(), q.getStartTime() == null ? null : q.getStartTime().getTime(), q.getStatus()
        );
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
