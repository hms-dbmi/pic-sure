package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Entity;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@Entity(name = "access_rule")
public class AccessRule extends BaseEntity {

    public static class TypeNaming {
//        public static final int CONTAINS = 0;
        public static final int NOT_CONTAINS = 1;
        public static final int NOT_CONTAINS_IGNORE_CASE = 2;
        public static final int NOT_EQUALS = 3;

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

    public String toString() {
    		return uuid.toString() + " ___ " + name + " ___ " + description + " ___ " + rule + " ___ " + type + " ___ " + value;
    }
}
