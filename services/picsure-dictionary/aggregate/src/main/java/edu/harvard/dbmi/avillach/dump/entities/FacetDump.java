package edu.harvard.dbmi.avillach.dump.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FacetDump implements DumpRow {
    private String name;
    private String display;
    private String description;
    private String facetCategoryName;
    private List<FacetDump> children = new ArrayList<>();
    private int facetID;
    private Integer parentID;
    private String parentName;

    public FacetDump() {}

    public FacetDump(
        String name, String display, String description, String facetCategoryName, List<FacetDump> children, int facetID, Integer parentID
    ) {
        this.name = name;
        this.display = display;
        this.description = description;
        this.facetCategoryName = facetCategoryName;
        this.children = children;
        this.facetID = facetID;
        this.parentID = parentID;
    }

    public FacetDump(String name, String display, String description, String facetCategoryName, int facetID, Integer parentID) {
        this(name, display, description, facetCategoryName, new ArrayList<>(), facetID, parentID);
    }

    public void addChild(FacetDump child) {
        children.add(child);
    }

    public String name() {
        return name;
    }

    public String display() {
        return display;
    }

    public String description() {
        return description;
    }

    public String facetCategoryName() {
        return facetCategoryName;
    }

    public List<FacetDump> children() {
        return children;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (FacetDump) obj;
        return Objects.equals(this.name, that.name) && Objects.equals(this.display, that.display)
            && Objects.equals(this.description, that.description) && Objects.equals(this.facetCategoryName, that.facetCategoryName)
            && Objects.equals(this.children, that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, display, description, facetCategoryName, children);
    }

    @Override
    public String toString() {
        return "FacetDump[" + "name=" + name + ", " + "display=" + display + ", " + "description=" + description + ", "
            + "facetCategoryName=" + facetCategoryName + ", " + "children=" + children + ']';
    }

    @JsonIgnore
    public int facetID() {
        return facetID;
    }

    @JsonIgnore
    public Integer parentID() {
        return parentID;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setFacetCategoryName(String facetCategoryName) {
        this.facetCategoryName = facetCategoryName;
    }

    public void setChildren(List<FacetDump> children) {
        this.children = children;
    }

    public void setFacetID(int facetID) {
        this.facetID = facetID;
    }

    public void setParentID(Integer parentID) {
        this.parentID = parentID;
    }

    public String parentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }
}
