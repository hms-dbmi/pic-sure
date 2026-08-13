package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.UserConsent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BdcConsentBasedAccessRuleEvaluator implements ConsentBasedAccessRuleEvaluator {

    private final Logger log = LoggerFactory.getLogger(BdcConsentBasedAccessRuleEvaluator.class);

    private static final String GENOMIC_AUTHORIZATION_FILTER = "\\_topmed_consents\\";
    private static final String HARMONIZED_AUTHORIZATION_FILTER = "\\_harmonized_consent\\";
    private static final Set<String> ALWAYS_ALLOWED_CONCEPT_ROOTS = Set.of("_Topmed Study Accession with Subject ID", "_Parent Study Accession with Subject ID", "_consents", "_harmonized_consent", "_topmed_consents");

    @Override
    public boolean evaluateAccessRule(Query query, AccessRule accessRule, UserConsents consents) {
        Set<String> userStudies = consents.getConsents().stream()
            .map(consent -> consent.split("\\.")[0]).collect(Collectors.toSet());

        for (PhenotypicFilter phenotypicFilter : query.allFilters()) {
            if (!isConceptPathAuthorized(phenotypicFilter.conceptPath(), consents, userStudies)) return false;
        }

        for (String conceptPath : query.select()) {
            if (!isConceptPathAuthorized(conceptPath, consents, userStudies)) return false;
        }

        return true;
    }

    private boolean isConceptPathAuthorized(String conceptPath, UserConsents consents, Set<String> userStudies) {
        // the 0th index of the array is empty because consents start with \\
        String[] split = conceptPath.split("\\\\");
        String filterConsent = split.length > 1 ? split[1] : split[0];

        if (ALWAYS_ALLOWED_CONCEPT_ROOTS.contains(filterConsent)) {
            return true;
        }
        if (filterConsent.equals("DCC Harmonized data set")) {
            return true;
        } else if (!userStudies.contains(filterConsent)) {
            log.debug("User does not have study: " + filterConsent + " to access " + conceptPath);
            return false;
        }
        return true;
    }

    @Override
    public Query setAuthorizationFiltersForQuery(UserConsents userConsents, Query query) {
        return query.setUserConsents(userConsents.getConsents().stream().map(UserConsent::new).collect(Collectors.toSet()));
    }
}
