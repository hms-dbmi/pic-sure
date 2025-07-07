package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import edu.harvard.dbmi.avillach.dictionary.dataset.DatasetService;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConceptService {

    private final ConceptRepository conceptRepository;
    private final DatasetService datasetService;
    private final ConceptDecoratorService conceptDecoratorService;

    @Autowired
    public ConceptService(
        ConceptRepository conceptRepository, DatasetService datasetService, ConceptDecoratorService conceptDecoratorService
    ) {
        this.conceptRepository = conceptRepository;
        this.datasetService = datasetService;
        this.conceptDecoratorService = conceptDecoratorService;
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
}
