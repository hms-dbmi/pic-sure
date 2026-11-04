package edu.harvard.hms.dbmi.avillach.query.aggregate;

import edu.harvard.dbmi.avillach.domain.BinRange;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;

/**
 * Dataset-wide concept metadata from the open HPDS {@code /search}, used to shape open charts without revealing anything about the cohort.
 */
@Component
public class ConceptMetadataLookup {

    private static final Logger logger = LoggerFactory.getLogger(ConceptMetadataLookup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AggregateBackendClient backend;

    public ConceptMetadataLookup(AggregateBackendClient backend) {
        this.backend = backend;
    }

    /**
     * The overall range of each continuous concept. Open continuous charts span it rather than the cohort's observed values, since where
     * the data stops would reveal empty ranges. A concept whose range is unknown is left out.
     */
    public Map<String, BinRange> overallRanges(Set<String> concepts) {
        Map<String, BinRange> ranges = new LinkedHashMap<>();
        for (String concept : concepts) {
            Map<String, Object> meta = metadata(concept);
            if (meta != null && meta.get("min") instanceof Number min && meta.get("max") instanceof Number max) {
                ranges.put(concept, new BinRange(min.doubleValue(), max.doubleValue()));
            } else {
                logger.warn("No overall range for continuous concept {}; leaving its chart out", concept);
            }
        }
        return ranges;
    }

    /** Every value each categorical concept has in the dataset. A concept whose values are unknown is left out. */
    public Map<String, Set<String>> categoryValues(Set<String> concepts) {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (String concept : concepts) {
            Map<String, Object> meta = metadata(concept);
            if (meta != null && meta.get("categoryValues") instanceof List<?> list && !list.isEmpty()) {
                Set<String> categories = new LinkedHashSet<>();
                list.forEach(value -> categories.add(String.valueOf(value)));
                values.put(concept, categories);
            }
        }
        return values;
    }

    private Map<String, Object> metadata(String concept) {
        SearchResults results = backend.search(new GeneralQueryRequest().setQuery(concept));
        Map<String, Object> body = MAPPER.convertValue(results.getResults(), new TypeReference<>() {});
        Map<String, Map<String, Object>> phenotypes =
            body == null ? Map.of() : MAPPER.convertValue(body.get("phenotypes"), new TypeReference<>() {});
        return Optional.ofNullable(phenotypes).map(p -> p.get(concept)).orElse(null);
    }
}
