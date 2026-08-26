package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class BdcConsentBasedAccessRuleEvaluator implements ConsentBasedAccessRuleEvaluator {

    private final Logger log = LoggerFactory.getLogger(BdcConsentBasedAccessRuleEvaluator.class);

    private static final String GENOMIC_AUTHORIZATION_FILTER = "\\_topmed_consents\\";
    private static final String HARMONIZED_AUTHORIZATION_FILTER = "\\_harmonized_consent\\";

    @Override
    public Query setAuthorizationFiltersForQuery(UserConsents userConsents, Query query) {
        List<AuthorizationFilter> authorizationFilter = userConsents.getConsents().entrySet().stream().filter(entry -> {
            if (entry.getKey().equals(GENOMIC_AUTHORIZATION_FILTER) && query.genomicFilters().isEmpty()) {
                return false;
            }
            if (entry.getKey().equals(HARMONIZED_AUTHORIZATION_FILTER)) {
                long harmonizedFilterCount =
                    query.allFilters().stream().filter(filter -> filter.conceptPath().startsWith("\\DCC Harmonized data set\\")).count();
                // leave these consents if there are any filters on harmonized concept paths
                return harmonizedFilterCount > 0;
            }
            return true;
        }).map(entry -> new AuthorizationFilter(entry.getKey(), entry.getValue())).toList();

        log.debug("Adding authorization filters to query:");
        authorizationFilter.stream().map(Objects::toString).forEach(log::debug);

        return query.setAuthorizationFilters(authorizationFilter);
    }
}
