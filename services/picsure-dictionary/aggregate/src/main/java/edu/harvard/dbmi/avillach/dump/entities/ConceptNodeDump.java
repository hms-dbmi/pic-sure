package edu.harvard.dbmi.avillach.dump.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConceptNodeDump implements DumpRow {

    private String datasetRef;
    private String name;
    private String display;
    private String conceptType;
    private String conceptPath;
    private List<ConceptNodeDump> children = new ArrayList<>();
    private Integer conceptNodeId;
    private Integer parentId;
    private String parentPath;

    public ConceptNodeDump() {}

    public void setParentId(Integer parentId) {
        this.parentId = parentId;
    }

    public void setConceptNodeId(Integer conceptNodeId) {
        this.conceptNodeId = conceptNodeId;
    }

    public void setDatasetRef(String datasetRef) {
        this.datasetRef = datasetRef;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public void setConceptType(String conceptType) {
        this.conceptType = conceptType;
    }

    public void setConceptPath(String conceptPath) {
        this.conceptPath = conceptPath;
    }

    public void setChildren(List<ConceptNodeDump> children) {
        this.children = children;
    }

    public ConceptNodeDump(
        String datasetRef, String name, String display, String conceptType, String conceptPath, Integer conceptNodeId, Integer parentId
    ) {
        this.datasetRef = datasetRef;
        this.name = name;
        this.display = display;
        this.conceptType = conceptType;
        this.conceptPath = conceptPath;
        this.conceptNodeId = conceptNodeId;
        this.parentId = parentId;
    }

    public String datasetRef() {
        return datasetRef;
    }

    public String name() {
        return name;
    }

    public String display() {
        return display;
    }

    public String conceptType() {
        return conceptType;
    }

    public String conceptPath() {
        return conceptPath;
    }

    public List<ConceptNodeDump> children() {
        return children;
    }

    public void addChild(ConceptNodeDump child) {
        children.add(child);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ConceptNodeDump) obj;
        return Objects.equals(this.datasetRef, that.datasetRef) && Objects.equals(this.name, that.name)
            && Objects.equals(this.display, that.display) && Objects.equals(this.conceptType, that.conceptType)
            && Objects.equals(this.conceptPath, that.conceptPath) && Objects.equals(this.children, that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetRef, name, display, conceptType, conceptPath, children);
    }

    @JsonIgnore
    public Integer conceptNodeId() {
        return conceptNodeId;
    }

    @JsonIgnore
    public Integer parentId() {
        return parentId;
    }

    @JsonIgnore
    public String parentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }
}
