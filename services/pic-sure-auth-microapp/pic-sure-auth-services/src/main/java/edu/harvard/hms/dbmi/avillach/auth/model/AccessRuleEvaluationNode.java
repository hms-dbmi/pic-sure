package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;

import java.util.ArrayList;
import java.util.List;

public class AccessRuleEvaluationNode {
    private final AccessRule rule;
    private final String nodeName;
    private final boolean isGate;
    private final boolean isSubRule;
    private final boolean isOrRelationship;
    private final List<AccessRuleEvaluationNode> children = new ArrayList<>();
    private boolean result;
    private String failureReason;
    private int depth = 0;

    public AccessRuleEvaluationNode(AccessRule rule, boolean isGate, boolean isSubRule, boolean isOrRelationship) {
        this.rule = rule;
        this.nodeName = rule.getMergedName().isEmpty() ? rule.getName() : rule.getMergedName();
        this.isGate = isGate;
        this.isSubRule = isSubRule;
        this.isOrRelationship = isOrRelationship;
    }

    public void addChild(AccessRuleEvaluationNode child) {
        child.depth = this.depth + 1;
        children.add(child);
    }

    public void setResult(boolean result) {
        this.result = result;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String generateTreeString() {
        StringBuilder sb = new StringBuilder();
        generateTreeString(sb, "", true);
        return sb.toString();
    }

    private void generateTreeString(StringBuilder sb, String prefix, boolean isTail) {
        String nodeType = isGate ? "GATE" : (isSubRule ? "SUB_RULE" : "RULE");
        String relationshipType = isOrRelationship ? "OR" : "AND";
        String resultStr = result ? "✓ PASS" : "✗ FAIL";
        String failureInfo = failureReason != null ? " - Reason: " + failureReason : "";

        sb.append(prefix)
          .append(isTail ? "└── " : "├── ")
          .append("[").append(nodeType).append("|").append(relationshipType).append("] ")
          .append(nodeName)
          .append(" (").append(resultStr).append(")")
          .append(failureInfo)
          .append("\n");

        for (int i = 0; i < children.size(); i++) {
            boolean isLastChild = (i == children.size() - 1);
            children.get(i).generateTreeString(sb, prefix + (isTail ? "    " : "│   "), isLastChild);
        }
    }

    public AccessRule getRule() { return rule; }
    public boolean isResult() { return result; }
    public List<AccessRuleEvaluationNode> getChildren() { return children; }
    public String getFailureReason() { return failureReason; }
}
