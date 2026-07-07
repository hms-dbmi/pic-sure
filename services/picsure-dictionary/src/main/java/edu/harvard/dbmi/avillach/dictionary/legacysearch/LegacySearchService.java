package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class LegacySearchService {

    private final LegacySearchRepository legacySearchRepository;

    @Autowired
    public LegacySearchService(LegacySearchRepository legacySearchRepository) {
        this.legacySearchRepository = legacySearchRepository;
    }

    public Results getSearchResults(Filter filter, Pageable pageable) {
        return new Results(legacySearchRepository.getLegacySearchResults(filter, pageable));
    }

}
