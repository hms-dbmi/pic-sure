package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.Optional;
import java.util.Set;

public record EvaluateAccessRuleResult(
    boolean result, Set<AccessRule> failedRules, String passRuleName, Optional<Query> query, Optional<String> denialReason
) {

    public EvaluateAccessRuleResult(boolean result, Set<AccessRule> failedRules, String passRuleName, Optional<Query> query) {
        this(result, failedRules, passRuleName, query, Optional.empty());
    }
}
