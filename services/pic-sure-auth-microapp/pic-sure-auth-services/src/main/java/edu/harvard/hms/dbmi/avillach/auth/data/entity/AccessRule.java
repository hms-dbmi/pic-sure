package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import java.lang.reflect.Field;
import java.util.HashMap;
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
        public static final int EQUALS = 4;
        public static final int ALL_CONTAINS = 5;
        public static final int CONTAINS_IGNORE_CASE = 6;
        public static final int ARRAY_CONTAINS = 7;
        public static final int NOT_EQUALS_IGNORE_CASE = 8;
        public static final int EQUALS_IGNORE_CASE = 9;
        public static final int ARRAY_EQUALS = 10;
        public static final int ALL_REG_MATCH = 11;
        public static final int ARRAY_REG_MATCH = 12;
        public static final int IS_EMPTY = 13;


        public static Map<String, Integer> getTypeNameMap(){
            Map<String, Integer> map = new HashMap<>();
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
     * @see TypeNaming
     */
    private int type;

    /**
     * The jsonpath rule to retrieve values
     * The possible value will be String, JSONObject, JSONArray, etc.
     */
    private String rule;

    /**
     * The value for checking
     */
    private String value;

    @ManyToOne
    private AccessRule gateParent;

    /**
     * Guideline of using gates: if null or empty, will skip checking gate
     * to pass gate settings, every gate in the set needs to be passed,
     * which means if only part of the gate set is passed, the gate still
     * not passed
     */
    @OneToMany(mappedBy = "gateParent")
    private Set<AccessRule> gates;

    @ManyToOne
    private AccessRule subAccessRuleParent;

    /**
     * introduce sub-accessRule to enable the ability of more complex problem
     */
    @OneToMany(mappedBy = "subAccessRuleParent")
    private Set<AccessRule> subAccessRule;

    private boolean checkMapNode;

    private boolean checkMapKeyOnly;

    public int getType() {
        return type;
    }

    public void setType(int type) {
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
    public AccessRule getGateParent() {
        return gateParent;
    }

    @JsonProperty("gateParent")
    public void setGateParent(AccessRule gateParent) {
        this.gateParent = gateParent;
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

    public boolean isCheckMapNode() {
        return checkMapNode;
    }

    public void setCheckMapNode(boolean checkMapNode) {
        this.checkMapNode = checkMapNode;
    }

    public boolean isCheckMapKeyOnly() {
        return checkMapKeyOnly;
    }

    public void setCheckMapKeyOnly(boolean checkMapKeyOnly) {
        this.checkMapKeyOnly = checkMapKeyOnly;
    }

    public String toString() {
    		return uuid.toString() + " ___ " + name + " ___ " + description + " ___ " + rule + " ___ " + type + " ___ " + value;
    }
}
