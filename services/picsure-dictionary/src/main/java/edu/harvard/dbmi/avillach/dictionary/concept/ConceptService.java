package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.persistence.ConceptJDBCRepository;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ConceptService {

    private final ConceptJDBCRepository conceptJDBCRepository;

    @Autowired
    public ConceptService(ConceptJDBCRepository conceptJDBCRepository) {
        this.conceptJDBCRepository = conceptJDBCRepository;
    }

    public List<Concept> listConcepts(Filter filter) {
        return conceptJDBCRepository.getConcepts(filter);
    }

    public Optional<Concept> conceptDetail(String dataset, String conceptPath) {
        return Optional.empty();
    }

    public Optional<Concept> conceptTree(String dataset, String conceptPath, int depth) {
        return Optional.empty();
    }
}
