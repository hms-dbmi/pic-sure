package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DumpService {
    private final DumpRepository repository;

    public DumpService(DumpRepository repository) {
        this.repository = repository;
    }

    public List<DumpRow> dumpTable(DumpTable table) {
        return switch (table) {
            // These two build their result by hand rather than streaming a RowMapper, so they are copied into
            // the sealed supertype here; every other branch already produces List<DumpRow> without copying.
            case ConceptNode -> List.copyOf(repository.getAllConcepts());
            case FacetCategory -> repository.getAllFacetCategories();
            case Facet -> List.copyOf(repository.getAllFacets());
            case FacetConceptNode -> repository.getAllFacetConceptPairs();
            case ConceptNodeMeta -> repository.getAllConceptNodeMetas();
            case FacetCategoryMeta -> repository.getAllFacetCategoryMetas();
            case FacetMeta -> repository.getAllFacetMetas();
        };
    }

    public LocalDateTime getLastUpdate() {
        return repository.getLastUpdated();
    }

    public Integer getDatabaseVersion() {
        return repository.getDatabaseVersion();
    }
}
