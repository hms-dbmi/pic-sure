package edu.harvard.hms.dbmi.avillach.auth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * <h3>Thoughts on design:</h3> the AccessRule is designed to fulfilled the requirements
 * of complicated scenarios that includes AND/OR or nested AND/OR cases of jsonPath authorization
 *</p>
 * <br>
 * <br>
 * <b>Attribute Explanations</b>:
 *     <li><b>checkMapNode</b> - after retrieving the value by jsonPath rule, if the value is a map,
 *     this flag will let the evaluation go through all the map nodes and their children nodes</li>
 *     <li><b>checkMapKeyOnly</b> - only take effective when checkMapNode flag is turned on. This flag will
 *     let the evaluation only check the key of current map node, it will stop the evaluation to go into
 *     the children nodes</li>
 *     <li><b>gateAnyRelation</b> - true: gates are evaluated as ANY relationship, false: gates are evaluated as AND relationship</li>
 *     <li><b>evaluateOnlyByGates</b> - this flag means no matter what rules and values are set,
 *     the evaluation will based on whether the gates are passed or not, which means if gates are passed,
 *     then evaluation result is true, not passed, return false. The use case for this flag is sometimes, we
 *     need to meet the requirements of some nested AND/OR gates like gateA && gateB && (gateC || gateD),
 *     in this example, (gateC || gateD) has to be together in a gate and not evaluate by the values and rules</li>
 *
 */
@Entity(name = "access_rule")
public class AccessRule extends BaseEntity {
    /**
     * please do not modify the existing values, in case the value has
     * already saved in the database. But you can add more constant values, or
     * update the keys.
     */
    public static class TypeNaming {
//        public static final int CONTAINS = 0;
        public static final int NOT_CONTAINS = 1;
        public static final int NOT_CONTAINS_IGNORE_CASE = 2;
        public static final int NOT_EQUALS = 3;
        public static final int ALL_EQUALS = 4;
        public static final int ALL_CONTAINS = 5;
        public static final int ALL_CONTAINS_IGNORE_CASE = 6;
        public static final int ANY_CONTAINS = 7;
        public static final int NOT_EQUALS_IGNORE_CASE = 8;
        public static final int ALL_EQUALS_IGNORE_CASE = 9;
        public static final int ANY_EQUALS = 10;
        public static final int ALL_REG_MATCH = 11;
        public static final int ANY_REG_MATCH = 12;
        public static final int IS_EMPTY = 13;
        public static final int IS_NOT_EMPTY = 14;
        public static final int ALL_CONTAINS_OR_EMPTY = 15;
        public static final int ALL_CONTAINS_OR_EMPTY_IGNORE_CASE = 16;

        public static Map<String, Integer> getTypeNameMap(){
            Map<String, Integer> map = new LinkedHashMap<>();
            for (Field f : AccessRule.TypeNaming.class.getDeclaredFields()){
                f.setAccessible(true);
                try {
                    map.put(f.getName(), (Integer)f.get(null));
                } catch (IllegalAccessException e){
                    continue;
                }
            }
            return map;
        }
    }

    private String name;

    private String description;

    /**
     * for check how to do with the retrieved value
     *
     *
     *    NOTICE: please don't change this back to int
     *    we need to support a null input,
     *    otherwise, the update mechanism will be broken
     *
     *
     * @see TypeNaming
     */
    private Integer type;

    /**
     * The jsonpath rule to retrieve values, kind of the route to the data.
     * The possible value will be String, JSONObject, JSONArray, etc.
     */
    private String rule;

    /**
     * The value for checking
     */
    private String value;

    /**
     * only inner use for merge accessRule
     * This field should neither be saved to database
     * nor seen by a user
     */
    @Transient
    private Set<String> mergedValues = new HashSet<>();

    /**
     * This attribute will not be seen by either endpoint users or database.
     * It is a intermediate product that generated on the fly for supporting
     * auto-merging functionality of accessRules when doing authorization.
     */
    @Transient
    private String mergedName = "";

    /**
     * Guideline of using gates: if null or empty, will skip checking gate
     * to pass gate settings, every gate in the set needs to be passed,
     * which means if only part of the gate set is passed, the gate still
     * not passed
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "accessRule_gate",
            joinColumns = {@JoinColumn(name = "accessRule_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "gate_id", nullable = false)})
    private Set<AccessRule> gates;

    /**
     * this attribute is for determining the relationship between gates
     * the default value is false, means gates are AND relationship,
     * meaning all gates need to be passed to check the actual rules
     *
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the update mechanism will be broken
     */
    @Column(name = "isGateAnyRelation")
    private Boolean gateAnyRelation;

    /**
     * this attribute is to tell if the accessRule passes only based on
     * the gates passes or not
     *
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the update mechanism will be broken
     */
    @Column(name = "isEvaluateOnlyByGates")
    private Boolean evaluateOnlyByGates;

    /**
     * introduce sub-accessRule to enable the ability of more complex problem, essentially it is an AND relationship.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "accessRule_subRule",
            joinColumns = {@JoinColumn(name = "accessRule_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "subRule_id", nullable = false)})
    private Set<AccessRule> subAccessRule;

    /**
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the auto update mechanism will be broken
     */
    private Boolean checkMapNode;

    /**
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the auto update mechanism will be broken
     */
    private Boolean checkMapKeyOnly;

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<AccessRule> getGates() {
        return gates;
    }

    public void setGates(Set<AccessRule> gates) {
        this.gates = gates;
    }

    public Boolean getEvaluateOnlyByGates() {
        return evaluateOnlyByGates;
    }

    public void setEvaluateOnlyByGates(Boolean evaluateOnlyByGates) {
        this.evaluateOnlyByGates = evaluateOnlyByGates;
    }

    public Set<AccessRule> getSubAccessRule() {
        return subAccessRule;
    }

    public void setSubAccessRule(Set<AccessRule> subAccessRule) {
        this.subAccessRule = subAccessRule;
    }

    public Boolean getCheckMapNode() {
        return checkMapNode;
    }

    public void setCheckMapNode(Boolean checkMapNode) {
        this.checkMapNode = checkMapNode;
    }

    public Boolean getCheckMapKeyOnly() {
        return checkMapKeyOnly;
    }

    public void setCheckMapKeyOnly(Boolean checkMapKeyOnly) {
        this.checkMapKeyOnly = checkMapKeyOnly;
    }

    public Set<String> getMergedValues() {
        return mergedValues;
    }

    public void setMergedValues(Set<String> mergedValues) {
        this.mergedValues = mergedValues;
    }

    public String getMergedName() {
        return mergedName;
    }

    public void setMergedName(String mergedName) {
        this.mergedName = mergedName;
    }

    public Boolean getGateAnyRelation() {
        return gateAnyRelation;
    }

    public void setGateAnyRelation(Boolean gateAnyRelation) {
        this.gateAnyRelation = gateAnyRelation;
    }

    public String toString() {
    		return uuid.toString() + " ___ " + name + " ___ " + description + " ___ " + rule + " ___ " + type + " ___ " + value;
    }
}
