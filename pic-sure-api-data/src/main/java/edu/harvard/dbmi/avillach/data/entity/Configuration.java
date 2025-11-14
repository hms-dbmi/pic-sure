package edu.harvard.dbmi.avillach.data.entity;

import java.sql.Date;
import java.util.Optional;
import javax.json.Json;
import javax.persistence.*;

import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Configuration object containing name, kind, enabled status, and description.")
@Entity(name = "configuration")
@Table(
    name = "configuration",
    uniqueConstraints = {@UniqueConstraint(name = "unique_uuid", columnNames = {"uuid"}),
        @UniqueConstraint(name = "unique_name_kind", columnNames = {"name", "kind"})}
)
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

    @Schema(description = "This configuration is flagged for deletion")
    private Boolean delete = false;

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

    public Configuration setDelete(Boolean delete) {
        this.delete = delete;
        return this;
    }

    public Boolean getDelete() {
        return delete;
    }

    @Override
    public String toString() {
        return Json.createObjectBuilder().add("uuid", uuid.toString()).add("name", name != null ? name : "")
            .add("kind", kind != null ? kind : "").add("value", value != null ? value : "")
            .add("description", description != null ? description : "").add("delete", delete != null ? value : "").build().toString();
    }

    public Configuration patch(ConfigurationRequest request) {
        if (request.getName() != null) this.setName(request.getName());
        if (request.getKind() != null) this.setKind(request.getKind());
        if (request.getValue() != null) this.setValue(request.getValue());
        if (request.getDescription() != null) this.setDescription(request.getDescription());
        if (request.getDelete() != null) this.setDelete(request.getDelete());

        return this;
    }

    public static Configuration fromRequest(ConfigurationRequest request) {
        Configuration config = new Configuration();
        return config.patch(request);
    }
}
