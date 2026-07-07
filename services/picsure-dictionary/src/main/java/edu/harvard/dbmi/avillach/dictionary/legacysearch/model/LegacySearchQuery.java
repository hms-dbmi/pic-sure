package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.data.domain.Pageable;

public record LegacySearchQuery(Filter filter, Pageable pageable) {
}
