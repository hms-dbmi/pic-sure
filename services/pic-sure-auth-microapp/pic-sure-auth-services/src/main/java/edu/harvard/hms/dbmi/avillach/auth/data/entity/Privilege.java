package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Entity(name = "privilege")
public class Privilege extends BaseEntity {

    @Column(unique = true)
    String name;

    String description;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "application_id")
    Application application;

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

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }
}
