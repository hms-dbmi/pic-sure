package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Column;
import javax.persistence.Entity;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Entity(name = "privilege")
public class Privilege extends BaseEntity {

    @Column(unique = true)
    String name;

    String description;

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
}
