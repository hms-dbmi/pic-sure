package edu.harvard.dbmi.avillach.data.entity;

import java.util.Map;

import javax.json.Json;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import io.swagger.v3.oas.annotations.media.Schema;

import edu.harvard.dbmi.avillach.data.entity.convert.JsonConverter;

@Schema(description = "A NamedDataset object containing query, name, user, and archived status.")
@Entity(name = "named_dataset")
@Table(uniqueConstraints = { 
    @UniqueConstraint(name = "unique_queryId_user", columnNames = { "queryId", "user" })
})
public class NamedDataset extends BaseEntity {
    @Schema(description = "The associated Query")
    @OneToOne
    @JoinColumn(name = "queryId")
    private Query query;

    @Schema(description = "The user identifier")
    @Column(length = 255)
    private String user;
    
    @Schema(description = "The name user has assigned to this dataset")
    @Column(length = 255)
    private String name;

    @Schema(description = "The archived state")
    private Boolean archived = false;

    @Schema(description = "A json string object containing override specific values")
    @Column(length = 8192)
    @Convert(converter = JsonConverter.class)
    private Map<String, Object> metadata;

    public NamedDataset setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public NamedDataset setArchived(Boolean archived) {
        this.archived = archived;
        return this;
    }

    public Boolean getArchived() {
        return archived;
    }

    public NamedDataset setQuery(Query query) {
        this.query = query;
        return this;
    }

    public Query getQuery(){
        return query;
    }

    public NamedDataset setUser(String user) {
        this.user = user;
        return this;
    }
    
    public String getUser(){
        return user;
    }

    public Map<String, Object> getMetadata(){
        return metadata;
    }

    public NamedDataset setMetadata(Map<String, Object> metadata){
        this.metadata = metadata;
        return this;
    }
    
    @Override
    public String toString() {
        return Json.createObjectBuilder()
            .add("uuid", uuid.toString())
            .add("name", name)
            .add("archived", archived)
            .add("queryId", query.getUuid().toString())
            .add("user", user)
            .add("metadata", metadata.toString())
            .build().toString();
    }
}
