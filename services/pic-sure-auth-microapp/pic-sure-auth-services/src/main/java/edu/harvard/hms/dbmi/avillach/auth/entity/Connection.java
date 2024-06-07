package edu.harvard.hms.dbmi.avillach.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.io.Serializable;

/**
 * <p>Defines a model of different supporting connections for the login process.</p>
 * <p>This class will eventually reflect changes in the ConnectionManagement page.</p>
 */
@Entity(name = "connection")
public class Connection extends BaseEntity implements Serializable {

    private String label;

    @Column(unique = true)
    private String id;
    private String subPrefix;

    private String requiredFields;

    public String getLabel() {
        return label;
    }

    public Connection setLabel(String label) {
        this.label = label;
        return this;
    }

    public String getId() {
        return id;
    }

    public Connection setId(String id) {
        this.id = id;
        return this;
    }

    public String getSubPrefix() {
        return subPrefix;
    }

    public Connection setSubPrefix(String subPrefix) {
        this.subPrefix = subPrefix;
        return this;
    }

    public String getRequiredFields() {
        return requiredFields;
    }

    public Connection setRequiredFields(String requiredFields) {
        this.requiredFields = requiredFields;
        return this;
    }
    
    public String toString() {
    	if(uuid == null) {
    		return "No UUID assgned___ " + id + " ___ " + subPrefix + " ___ " + label + " ___ " + requiredFields;
    	}
    	return uuid.toString() + " ___ " + id + " ___ " + subPrefix + " ___ " + label + " ___ " + requiredFields;
    }
}


