package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import java.io.Serializable;

/**
 * This class will eventually reflect the changes in the page of ConnectionManagement in UI
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
    		return uuid.toString() + " ___ " + id + " ___ " + subPrefix + " ___ " + label + " ___ " + requiredFields;
    }
}


