package edu.harvard.hms.dbmi.avillach.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;

import java.util.Date;

/**
 * <p>Defines a model for terms of service.</p>
 */
@Entity(name = "termsOfService")
public class TermsOfService extends BaseEntity {

    private String content;

    private Date dateUpdated;

    public String getContent() {
        return content;
    }

    @PrePersist
    protected void onCreate() {
        dateUpdated = new Date();
    }
    public TermsOfService setContent(String content) {
        this.content = content;
        return this;
    }

    public Date getDateUpdated() {
        return dateUpdated;
    }

    public TermsOfService setDateUpdated(Date date){
        this.dateUpdated = date;
        return this;
    }
}
