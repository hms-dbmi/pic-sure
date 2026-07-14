package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Demonstration class for AccessRuleEvaluationNode.
 * This class provides examples of how AccessRuleEvaluationNode is used to build and print
 * the access rule and gate evaluation tree.
 */
public class AccessRuleEvaluationNodeDemo {

    @Test
    public void demonstrateAccessRuleEvaluationNode() {
        System.out.println("AccessRuleEvaluationNode Demo");
        System.out.println("=============================");

        demonstrateSimpleTree();
        demonstrateComplexTree();
    }

    /**
     * Demonstrates a simple tree with a parent and two child nodes
     */
    private static void demonstrateSimpleTree() {
        System.out.println("\n1. Simple Tree Example");
        System.out.println("---------------------");

        AccessRule parentRule = createSimpleRule("ParentRule", "$.parent", AccessRule.TypeNaming.ALL_EQUALS, "parentValue");
        AccessRuleEvaluationNode parentNode = new AccessRuleEvaluationNode(parentRule, false, false, false);
        parentNode.setResult(true);

        AccessRule childRule1 = createSimpleRule("ChildRule1", "$.child1", AccessRule.TypeNaming.ALL_EQUALS, "childValue1");
        AccessRuleEvaluationNode childNode1 = new AccessRuleEvaluationNode(childRule1, false, true, false);
        childNode1.setResult(true);

        AccessRule childRule2 = createSimpleRule("ChildRule2", "$.child2", AccessRule.TypeNaming.ALL_EQUALS, "childValue2");
        AccessRuleEvaluationNode childNode2 = new AccessRuleEvaluationNode(childRule2, false, true, false);
        childNode2.setResult(false);
        childNode2.setFailureReason("Value mismatch");

        parentNode.addChild(childNode1);
        parentNode.addChild(childNode2);

        String treeString = parentNode.generateTreeString();
        System.out.println(treeString);
    }

    /**
     * Demonstrates a complex tree with gates, sub-rules, and different relationship types
     */
    private static void demonstrateComplexTree() {
        System.out.println("\n2. Complex Tree Example");
        System.out.println("----------------------");

        AccessRule rootRule = createSimpleRule("RootRule", "$.root", AccessRule.TypeNaming.ALL_EQUALS, "rootValue");
        AccessRuleEvaluationNode rootNode = new AccessRuleEvaluationNode(rootRule, false, false, false);
        rootNode.setResult(true);

        AccessRule gateRule1 = createSimpleRule("GateRule1", "$.gate1", AccessRule.TypeNaming.ALL_EQUALS, "gateValue1");
        AccessRuleEvaluationNode gateNode1 = new AccessRuleEvaluationNode(gateRule1, true, false, false);
        gateNode1.setResult(true);

        AccessRule gateRule2 = createSimpleRule("GateRule2", "$.gate2", AccessRule.TypeNaming.ALL_EQUALS, "gateValue2");
        AccessRuleEvaluationNode gateNode2 = new AccessRuleEvaluationNode(gateRule2, true, false, true);
        gateNode2.setResult(false);
        gateNode2.setFailureReason("Gate condition not met");

        AccessRule subRule1 = createSimpleRule("SubRule1", "$.sub1", AccessRule.TypeNaming.ALL_EQUALS, "subValue1");
        AccessRuleEvaluationNode subNode1 = new AccessRuleEvaluationNode(subRule1, false, true, false);
        subNode1.setResult(true);

        AccessRule subRule2 = createSimpleRule("SubRule2", "$.sub2", AccessRule.TypeNaming.ALL_EQUALS, "subValue2");
        AccessRuleEvaluationNode subNode2 = new AccessRuleEvaluationNode(subRule2, false, true, true);
        subNode2.setResult(false);
        subNode2.setFailureReason("Sub-rule condition not met");

        AccessRule nestedRule = createSimpleRule("NestedRule", "$.nested", AccessRule.TypeNaming.ALL_EQUALS, "nestedValue");
        AccessRuleEvaluationNode nestedNode = new AccessRuleEvaluationNode(nestedRule, false, true, false);
        nestedNode.setResult(true);

        AccessRule nestedChildRule = createSimpleRule("NestedChild", "$.nestedChild", AccessRule.TypeNaming.ALL_EQUALS, "nestedChildValue");
        AccessRuleEvaluationNode nestedChildNode = new AccessRuleEvaluationNode(nestedChildRule, true, false, false);
        nestedChildNode.setResult(true);

        rootNode.addChild(gateNode1);
        rootNode.addChild(gateNode2);
        rootNode.addChild(subNode1);
        rootNode.addChild(subNode2);

        subNode1.addChild(nestedNode);
        nestedNode.addChild(nestedChildNode);

        String treeString = rootNode.generateTreeString();
        System.out.println(treeString);

        // Explanation of the tree structure
        System.out.println("\nTree Structure Explanation:");
        System.out.println("- The root node is a regular RULE with AND relationship");
        System.out.println("- It has two GATE children (one with AND, one with OR relationship)");
        System.out.println("- It has two SUB_RULE children (one with AND, one with OR relationship)");
        System.out.println("- The first SUB_RULE has a nested SUB_RULE child");
        System.out.println("- The nested SUB_RULE has a GATE child");
        System.out.println("- Nodes marked with ✓ PASS have passed their evaluation");
        System.out.println("- Nodes marked with ✗ FAIL have failed their evaluation");
        System.out.println("- Failed nodes include a failure reason");
    }

    /**
     * Helper method to create a simple AccessRule
     */
    private static AccessRule createSimpleRule(String name, String rule, Integer type, String value) {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName(name);
        accessRule.setRule(rule);
        accessRule.setType(type);
        accessRule.setValue(value);
        return accessRule;
    }
}
