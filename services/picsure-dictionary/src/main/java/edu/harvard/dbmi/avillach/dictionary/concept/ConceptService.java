package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportMetadata;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportRequest;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import edu.harvard.dbmi.avillach.dictionary.dataset.DatasetService;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class ConceptService {

    /** Largest number of concept paths one export metadata request may carry. */
    public static final int MAX_EXPORT_CONCEPT_PATHS = 5000;

    private final ConceptRepository conceptRepository;
    private final DatasetService datasetService;
    private final ConceptDecoratorService conceptDecoratorService;
    private final Set<String> internalExportKeys;

    @Autowired
    public ConceptService(
        ConceptRepository conceptRepository, DatasetService datasetService, ConceptDecoratorService conceptDecoratorService,
        @Value("${metadata.internal_export_keys:drs_uri}") Set<String> internalExportKeys
    ) {
        this.conceptRepository = conceptRepository;
        this.datasetService = datasetService;
        this.conceptDecoratorService = conceptDecoratorService;
        this.internalExportKeys = Set.copyOf(internalExportKeys);
    }

    @Cacheable("concepts")
    public List<Concept> listConcepts(Filter filter, Pageable page) {
        return conceptRepository.getConcepts(filter, page);
    }

    public List<Concept> listDetailedConcepts(Filter filter, Pageable page) {
        List<Concept> concepts = conceptRepository.getConcepts(filter, page);
        Map<Concept, Map<String, String>> metas = conceptRepository.getConceptMetaForConcepts(concepts);
        return concepts.stream().map(concept -> (Concept) switch (concept) {
            case ContinuousConcept cont -> new ContinuousConcept(cont, metas.getOrDefault(cont, Map.of()));
            case CategoricalConcept cat -> new CategoricalConcept(cat, metas.getOrDefault(cat, Map.of()));
            case ConceptShell ignored -> throw new RuntimeException("Concept shell escaped to API");
        }).toList();
    }

    @Cacheable("concepts_count")
    public long countConcepts(Filter filter) {
        return conceptRepository.countConcepts(filter);
    }

    public Optional<Concept> conceptDetail(String dataset, String conceptPath) {
        return getConcept(dataset, conceptPath, true);
    }

    private Optional<Concept> getConcept(String dataset, String conceptPath, boolean addAncestors) {
        Optional<Concept> concept = conceptRepository.getConcept(dataset, conceptPath).map(core -> {
            var meta = conceptRepository.getConceptMeta(dataset, conceptPath);
            return switch (core) {
                case ContinuousConcept cont -> new ContinuousConcept(cont, meta);
                case CategoricalConcept cat -> new CategoricalConcept(cat, meta);
                case ConceptShell ignored -> throw new RuntimeException("Concept shell escaped to API");
            };
        });
        return addAncestors ? concept.map(conceptDecoratorService::populateParentConcepts) : concept;
    }

    public Optional<Concept> conceptTree(String dataset, String conceptPath, int depth) {
        return conceptRepository.getConceptTree(dataset, conceptPath, depth);
    }

    public List<Concept> conceptHierarchy(String dataset, String conceptPath) {
        return conceptRepository.getConceptHierarchy(dataset, conceptPath);
    }


    public List<Concept> allConceptTrees(int depth) {
        return datasetService.getAllDatasets().stream().map(Dataset::ref).map(ref -> conceptTree(ref, null, depth))
            .filter(Optional::isPresent).map(Optional::get).toList();
    }

    public Optional<Concept> conceptDetailWithoutAncestors(String dataset, String conceptPath) {
        return getConcept(dataset, conceptPath, false);
    }

    public List<Concept> conceptsWithDetail(List<String> conceptPaths) {
        return this.conceptRepository.getConceptsByPathWithMetadata(conceptPaths);
    }

    /**
     * Looks up what an export writes about each requested concept, with metadata keys as stored and without the no-show list applied.
     *
     * @param request the concept paths and the raw metadata keys to return
     * @return one row per concept found, empty when no paths were requested; a path that matches no concept is left out
     * @throws InvalidConceptExportRequestException if either list is null or holds a null, there are more than
     *         {@value #MAX_EXPORT_CONCEPT_PATHS} paths, or a metadata key is not in {@code metadata.internal_export_keys}
     */
    public List<ConceptExportMetadata> exportMetadata(ConceptExportRequest request) {
        List<String> conceptPaths = request.conceptPaths();
        List<String> metaKeys = request.metaKeys();
        if (conceptPaths == null || metaKeys == null) {
            throw new InvalidConceptExportRequestException("conceptPaths and metaKeys are both required");
        }
        if (conceptPaths.size() > MAX_EXPORT_CONCEPT_PATHS) {
            throw new InvalidConceptExportRequestException(
                "At most " + MAX_EXPORT_CONCEPT_PATHS + " concept paths are allowed in one request but there were " + conceptPaths.size()
            );
        }
        if (conceptPaths.stream().anyMatch(Objects::isNull)) {
            throw new InvalidConceptExportRequestException("conceptPaths must not hold a null");
        }
        if (metaKeys.stream().anyMatch(key -> key == null || !internalExportKeys.contains(key))) {
            throw new InvalidConceptExportRequestException("metaKeys holds a key that is not allowed for export");
        }
        if (conceptPaths.isEmpty()) {
            return List.of();
        }
        return conceptRepository.getExportMetadata(conceptPaths, metaKeys);
    }
}
