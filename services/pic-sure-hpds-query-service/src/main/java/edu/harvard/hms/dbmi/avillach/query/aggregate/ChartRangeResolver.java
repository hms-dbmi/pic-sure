package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.LinkedHashMap;
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
 * Open continuous charts span the concept's overall range across the whole dataset, never the cohort's observed values, since where the
 * data stops would reveal empty ranges.
 */
@Component
public class ChartRangeResolver {

    private static final Logger logger = LoggerFactory.getLogger(ChartRangeResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AggregateBackendClient backend;

    public ChartRangeResolver(AggregateBackendClient backend) {
        this.backend = backend;
    }

    /** Overall range for each concept. A concept whose overall range is unknown is left out. */
    public Map<String, ChartRange> resolve(Set<String> concepts) {
        Map<String, ChartRange> ranges = new LinkedHashMap<>();
        for (String concept : concepts) {
            Optional<ChartRange> overall = overallRange(concept);
            if (overall.isPresent()) {
                ranges.put(concept, overall.get());
            } else {
                logger.warn("No overall range for continuous concept {}; leaving its chart out", concept);
            }
        }
        return ranges;
    }

    private Optional<ChartRange> overallRange(String concept) {
        SearchResults results = backend.search(new GeneralQueryRequest().setQuery(concept));
        Map<String, Object> body = MAPPER.convertValue(results.getResults(), new TypeReference<>() {});
        Map<String, Map<String, Object>> phenotypes =
            body == null ? Map.of() : MAPPER.convertValue(body.get("phenotypes"), new TypeReference<>() {});
        Map<String, Object> meta = phenotypes == null ? null : phenotypes.get(concept);
        if (meta == null || !(meta.get("min") instanceof Number min) || !(meta.get("max") instanceof Number max)) {
            return Optional.empty();
        }
        return Optional.of(new ChartRange(min.doubleValue(), max.doubleValue()));
    }
}
