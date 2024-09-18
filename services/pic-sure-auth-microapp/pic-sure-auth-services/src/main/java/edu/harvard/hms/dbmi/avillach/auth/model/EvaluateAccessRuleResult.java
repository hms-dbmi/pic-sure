package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;

import java.util.Set;

public record EvaluateAccessRuleResult(boolean result, Set<AccessRule> failedRules, String passRuleName) {
}
