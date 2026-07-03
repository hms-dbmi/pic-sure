package edu.harvard.hms.dbmi.avillach.operations.dataset;

import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;

/**
 * Translates between the {@code pic-sure-api-data} {@link NamedDataset} entity and this service's DTOs. Pure field mapping -- the
 * {@code Query} referenced by {@code queryId} is resolved/persisted by {@link NamedDatasetService} (via {@code QueryRepository}) and handed
 * in already-loaded, mirroring the legacy WAR's {@code NamedDatasetService}.
 */
@Component
public class NamedDatasetMapper {

    public NamedDatasetDto toDto(NamedDataset e) {
        return new NamedDatasetDto(
            e.getUuid(), e.getName(), e.getQuery() == null ? null : e.getQuery().getUuid(), e.getArchived(), e.getMetadata()
        );
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
