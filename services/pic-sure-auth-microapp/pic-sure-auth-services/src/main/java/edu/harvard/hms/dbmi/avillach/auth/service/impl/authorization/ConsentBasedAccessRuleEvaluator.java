package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

public interface ConsentBasedAccessRuleEvaluator {
    boolean evaluateAccessRule(Query query, AccessRule accessRule, UserConsents consents);

    Query setAuthorizationFiltersForQuery(UserConsents userConsents, Query query);
}
