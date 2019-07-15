package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Entity(name = "access_rule")
public class AccessRule extends BaseEntity {

    /**
     * please do not modify the existing values, in case the value has
     * already saved in the database. But you can add more constant values
     */
    public static class TypeNaming {
//        public static final int CONTAINS = 0;
        public static final int NOT_CONTAINS = 1;
        public static final int NOT_CONTAINS_IGNORE_CASE = 2;
        public static final int NOT_EQUALS = 3;
        public static final int ALL_EQUALS = 4;
        public static final int ALL_CONTAINS = 5;
        public static final int ALL_CONTAINS_IGNORE_CASE = 6;
        public static final int ARRAY_CONTAINS = 7;
        public static final int NOT_EQUALS_IGNORE_CASE = 8;
        public static final int ALL_EQUALS_IGNORE_CASE = 9;
        public static final int ARRAY_EQUALS = 10;
        public static final int ALL_REG_MATCH = 11;
        public static final int ARRAY_REG_MATCH = 12;
        public static final int IS_EMPTY = 13;


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
     * The jsonpath rule to retrieve values
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
    @JsonIgnore
    @Transient
    private Set<String> mergedValues = new HashSet<>();

    @JsonIgnore
    @Transient
    private String mergedName = "";

    /**
     * Guideline of using gates: if null or empty, will skip checking gate
     * to pass gate settings, every gate in the set needs to be passed,
     * which means if only part of the gate set is passed, the gate still
     * not passed
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "accessRule_gate",
            joinColumns = {@JoinColumn(name = "accessRule_id", nullable = false, updatable = false)},
            inverseJoinColumns = {@JoinColumn(name = "gate_id", nullable = false, updatable = false)})
    private Set<AccessRule> gates;

    @ManyToOne
    private AccessRule subAccessRuleParent;

    /**
     * introduce sub-accessRule to enable the ability of more complex problem
     */
    @OneToMany(mappedBy = "subAccessRuleParent")
    private Set<AccessRule> subAccessRule;

    /**
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the update mechanism will be broken
     */
    private Boolean checkMapNode;

    /**
     * NOTICE: please don't change this back to boolean
     * we need to support a null input,
     * otherwise, the update mechanism will be broken
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

    @JsonIgnore
    public AccessRule getSubAccessRuleParent() {
        return subAccessRuleParent;
    }

    @JsonProperty("subAccessRuleParent")
    public void setSubAccessRuleParent(AccessRule subAccessRuleParent) {
        this.subAccessRuleParent = subAccessRuleParent;
    }

    public Set<AccessRule> getGates() {
        return gates;
    }

    public void setGates(Set<AccessRule> gates) {
        this.gates = gates;
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

    public String toString() {
    		return uuid.toString() + " ___ " + name + " ___ " + description + " ___ " + rule + " ___ " + type + " ___ " + value;
    }
}
