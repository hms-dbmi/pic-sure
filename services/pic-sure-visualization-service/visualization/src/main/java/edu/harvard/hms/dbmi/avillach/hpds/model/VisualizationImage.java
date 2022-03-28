package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

@Data
public class VisualizationImage {
    private String image;
    private String title;
    private String description;
    private String chartType;

    public VisualizationImage(String image, String title, String chartType) {
        this.image = image;
        this.title = title;
        this.chartType = chartType;
    }

    public String getDescription() {
        if (description == null) {
            description = "A " + chartType + " of " + title + ".";
        }
        return description;
    }
}
