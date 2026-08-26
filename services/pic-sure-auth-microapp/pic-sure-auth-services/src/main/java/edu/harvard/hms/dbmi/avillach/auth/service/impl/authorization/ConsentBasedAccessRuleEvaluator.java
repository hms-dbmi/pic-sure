package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

public interface ConsentBasedAccessRuleEvaluator {
    Query setAuthorizationFiltersForQuery(UserConsents userConsents, Query query);
}
