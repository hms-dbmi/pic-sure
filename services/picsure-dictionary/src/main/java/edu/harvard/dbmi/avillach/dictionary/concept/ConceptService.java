package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ConceptService {

    private final ConceptRepository conceptRepository;

    @Autowired
    public ConceptService(ConceptRepository conceptRepository) {
        this.conceptRepository = conceptRepository;
    }

    public List<Concept> listConcepts(Filter filter, Pageable page) {
        return conceptRepository.getConcepts(filter, page);
    }

    public long countConcepts(Filter filter) {
        return conceptRepository.countConcepts(filter);
    }

    public Optional<Concept> conceptDetail(String dataset, String conceptPath) {
        return conceptRepository.getConcept(dataset, conceptPath)
            .map(core -> {
                var meta = conceptRepository.getConceptMeta(dataset, conceptPath);
                return switch (core) {
                    case ContinuousConcept cont -> new ContinuousConcept(cont, meta);
                    case CategoricalConcept cat -> new CategoricalConcept(cat, meta);
                };
            }
        );
    }

    public Optional<Concept> conceptTree(String dataset, String conceptPath, int depth) {
        return Optional.empty();
    }
}
