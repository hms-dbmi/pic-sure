package edu.harvard.hms.dbmi.avillach.query.consent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

@Component
public class ConsentFilterBuilder {

    static final String CONSENT_PATH = "\\_consents\\";
    static final String GENOMIC_CONSENT_PATH = "\\_topmed_consents\\";
    static final String HARMONIZED_CONSENT_PATH = "\\_harmonized_consent\\";
    private static final String HARMONIZED_DATASET_PREFIX = "\\DCC Harmonized data set\\";

    public Query apply(Query query, Map<String, Set<String>> consents) {
        List<AuthorizationFilter> filters = new ArrayList<>();
        filters.add(new AuthorizationFilter(CONSENT_PATH, requiredConsent(consents, CONSENT_PATH)));
        if (!query.genomicFilters().isEmpty()) {
            filters.add(new AuthorizationFilter(GENOMIC_CONSENT_PATH, requiredConsent(consents, GENOMIC_CONSENT_PATH)));
        }
        if (query.allFilters().stream().anyMatch(filter -> filter.conceptPath().startsWith(HARMONIZED_DATASET_PREFIX))) {
            filters.add(new AuthorizationFilter(HARMONIZED_CONSENT_PATH, requiredConsent(consents, HARMONIZED_CONSENT_PATH)));
        }
        return query.setAuthorizationFilters(filters);
    }

    private static Set<String> requiredConsent(Map<String, Set<String>> consents, String path) {
        Set<String> values = consents.get(path);
        if (values == null || values.isEmpty()) {
            throw new PicsureException(HttpStatus.FORBIDDEN, "consent_denied", "Consent does not permit this query");
        }
        return values;
    }
}
