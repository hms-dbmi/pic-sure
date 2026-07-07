package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for AccessRuleEvaluationNode.
 * This class demonstrates how AccessRuleEvaluationNode is used to build and print
 * the access rule and gate evaluation tree.
 */
public class AccessRuleEvaluationNodeTest {

    /**
     * Test creating a simple node and verifying its properties
     */
    @Test
    public void testCreateSimpleNode() {
        AccessRule rule = createSimpleRule("TestRule", "$.test", AccessRule.TypeNaming.ALL_EQUALS, "value");
        
        AccessRuleEvaluationNode node = new AccessRuleEvaluationNode(rule, false, false, false);
        
        assertEquals(rule, node.getRule());
        assertFalse(node.isResult());
        assertTrue(node.getChildren().isEmpty());
        assertNull(node.getFailureReason());
    }
    
    /**
     * Test setting result and failure reason
     */
    @Test
    public void testSetResultAndFailureReason() {
        AccessRule rule = createSimpleRule("TestRule", "$.test", AccessRule.TypeNaming.ALL_EQUALS, "value");
        
        AccessRuleEvaluationNode node = new AccessRuleEvaluationNode(rule, false, false, false);
        
        node.setResult(true);
        assertTrue(node.isResult());
        
        node.setFailureReason("Test failure reason");
        assertEquals("Test failure reason", node.getFailureReason());
    }
    
    /**
     * Test adding child nodes
     */
    @Test
    public void testAddChildNodes() {
        AccessRule parentRule = createSimpleRule("ParentRule", "$.parent", AccessRule.TypeNaming.ALL_EQUALS, "parentValue");
        AccessRule childRule1 = createSimpleRule("ChildRule1", "$.child1", AccessRule.TypeNaming.ALL_EQUALS, "childValue1");
        AccessRule childRule2 = createSimpleRule("ChildRule2", "$.child2", AccessRule.TypeNaming.ALL_EQUALS, "childValue2");
        
        AccessRuleEvaluationNode parentNode = new AccessRuleEvaluationNode(parentRule, false, false, false);
        AccessRuleEvaluationNode childNode1 = new AccessRuleEvaluationNode(childRule1, false, false, false);
        AccessRuleEvaluationNode childNode2 = new AccessRuleEvaluationNode(childRule2, false, false, false);
        
        parentNode.addChild(childNode1);
        parentNode.addChild(childNode2);
        
        assertEquals(2, parentNode.getChildren().size());
        assertTrue(parentNode.getChildren().contains(childNode1));
        assertTrue(parentNode.getChildren().contains(childNode2));
    }
    
    /**
     * Test generating tree string for a simple node
     */
    @Test
    public void testGenerateTreeStringSimpleNode() {
        AccessRule rule = createSimpleRule("TestRule", "$.test", AccessRule.TypeNaming.ALL_EQUALS, "value");
        
        AccessRuleEvaluationNode node = new AccessRuleEvaluationNode(rule, false, false, false);
        node.setResult(true);
        
        String treeString = node.generateTreeString();
        
        System.out.println("Simple Node Tree:");
        System.out.println(treeString);
        
        assertTrue(treeString.contains("TestRule"));
        assertTrue(treeString.contains("RULE"));
        assertTrue(treeString.contains("AND"));
        assertTrue(treeString.contains("✓ PASS"));
    }
    
    /**
     * Test generating tree string for a node with children
     */
    @Test
    public void testGenerateTreeStringWithChildren() {
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
        
        System.out.println("Tree with Children:");
        System.out.println(treeString);
        
        assertTrue(treeString.contains("ParentRule"));
        assertTrue(treeString.contains("RULE"));
        assertTrue(treeString.contains("✓ PASS"));
        
        assertTrue(treeString.contains("ChildRule1"));
        assertTrue(treeString.contains("SUB_RULE"));
        assertTrue(treeString.contains("✓ PASS"));
        
        assertTrue(treeString.contains("ChildRule2"));
        assertTrue(treeString.contains("✗ FAIL"));
        assertTrue(treeString.contains("Value mismatch"));
    }
    
    /**
     * Test generating tree string for a complex tree with gates and different relationship types
     */
    @Test
    public void testGenerateTreeStringComplexTree() {
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
        
        rootNode.addChild(gateNode1);
        rootNode.addChild(gateNode2);
        rootNode.addChild(subNode1);
        rootNode.addChild(subNode2);
        
        String treeString = rootNode.generateTreeString();
        
        System.out.println("Complex Tree:");
        System.out.println(treeString);
        
        assertTrue(treeString.contains("RootRule"));
        assertTrue(treeString.contains("RULE"));
        assertTrue(treeString.contains("AND"));
        assertTrue(treeString.contains("✓ PASS"));
        
        assertTrue(treeString.contains("GateRule1"));
        assertTrue(treeString.contains("GATE"));
        assertTrue(treeString.contains("AND"));
        assertTrue(treeString.contains("✓ PASS"));
        
        assertTrue(treeString.contains("GateRule2"));
        assertTrue(treeString.contains("GATE"));
        assertTrue(treeString.contains("OR"));
        assertTrue(treeString.contains("✗ FAIL"));
        assertTrue(treeString.contains("Gate condition not met"));
        
        assertTrue(treeString.contains("SubRule1"));
        assertTrue(treeString.contains("SUB_RULE"));
        assertTrue(treeString.contains("AND"));
        assertTrue(treeString.contains("✓ PASS"));
        
        assertTrue(treeString.contains("SubRule2"));
        assertTrue(treeString.contains("SUB_RULE"));
        assertTrue(treeString.contains("OR"));
        assertTrue(treeString.contains("✗ FAIL"));
        assertTrue(treeString.contains("Sub-rule condition not met"));
    }
    
    /**
     * Test a deep tree structure with multiple levels
     */
    @Test
    public void testDeepTreeStructure() {
        // Create a deep tree with multiple levels
        AccessRule level1Rule = createSimpleRule("Level1", "$.level1", AccessRule.TypeNaming.ALL_EQUALS, "value1");
        AccessRuleEvaluationNode level1Node = new AccessRuleEvaluationNode(level1Rule, false, false, false);
        level1Node.setResult(true);
        
        AccessRule level2Rule = createSimpleRule("Level2", "$.level2", AccessRule.TypeNaming.ALL_EQUALS, "value2");
        AccessRuleEvaluationNode level2Node = new AccessRuleEvaluationNode(level2Rule, true, false, false);
        level2Node.setResult(true);
        
        AccessRule level3Rule = createSimpleRule("Level3", "$.level3", AccessRule.TypeNaming.ALL_EQUALS, "value3");
        AccessRuleEvaluationNode level3Node = new AccessRuleEvaluationNode(level3Rule, false, true, false);
        level3Node.setResult(true);
        
        AccessRule level4Rule = createSimpleRule("Level4", "$.level4", AccessRule.TypeNaming.ALL_EQUALS, "value4");
        AccessRuleEvaluationNode level4Node = new AccessRuleEvaluationNode(level4Rule, true, false, true);
        level4Node.setResult(false);
        level4Node.setFailureReason("Deep level failure");
        
        level1Node.addChild(level2Node);
        level2Node.addChild(level3Node);
        level3Node.addChild(level4Node);
        
        String treeString = level1Node.generateTreeString();
        
        System.out.println("Deep Tree Structure:");
        System.out.println(treeString);
        
        assertTrue(treeString.contains("Level1"));
        assertTrue(treeString.contains("Level2"));
        assertTrue(treeString.contains("Level3"));
        assertTrue(treeString.contains("Level4"));
        assertTrue(treeString.contains("Deep level failure"));
    }

    /**
     * Helper method to create a simple AccessRule
     */
    private AccessRule createSimpleRule(String name, String rule, Integer type, String value) {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName(name);
        accessRule.setRule(rule);
        accessRule.setType(type);
        accessRule.setValue(value);
        return accessRule;
    }
}