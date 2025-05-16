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

    public List<? extends DumpRow> dumpTable(DumpTable table) {
        return switch (table) {
            case ConceptNode -> repository.getAllConcepts();
            case FacetCategory -> repository.getAllFacetCategories();
            case Facet -> repository.getAllFacets();
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
