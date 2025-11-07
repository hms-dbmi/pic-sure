package edu.harvard.dbmi.avillach.data.entity;

import javax.json.Json;
import javax.persistence.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Configuration object containing name, kind, enabled status, and description.")
@Entity(name = "configuration")
@Table(name = "configuration", uniqueConstraints = {@UniqueConstraint(name = "unique_name", columnNames = {"uuid", "name"})})
public class Configuration extends BaseEntity {
    @Schema(description = "The configuration name")
    @Column(length = 255)
    private String name;

    @Schema(description = "The configuration kind/type")
    @Column(length = 255)
    private String kind;

    @Schema(description = "The configuration description")
    @Lob
    @Column(columnDefinition = "TEXT")
    private String value;

    @Schema(description = "The configuration value")
    @Column(length = 255)
    private String description;

    public Configuration setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public Configuration setKind(String kind) {
        this.kind = kind;
        return this;
    }

    public String getKind() {
        return kind;
    }

    public Configuration setValue(String value) {
        this.value = value;
        return this;
    }

    public String getValue() {
        return value;
    }

    public Configuration setDescription(String description) {
        this.description = description;
        return this;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return Json.createObjectBuilder().add("uuid", uuid.toString()).add("name", name != null ? name : "")
            .add("kind", kind != null ? kind : "").add("value", value != null ? value : "")
            .add("description", description != null ? description : "").build().toString();
    }
}
