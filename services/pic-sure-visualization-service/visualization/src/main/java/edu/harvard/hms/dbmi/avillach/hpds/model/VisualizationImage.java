package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

import java.util.Locale;

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
        if (description == null && this.title != null) {
            description = this.chartType + " showing the " + this.title.toLowerCase() + " for the selected cohort.";
        }
        return description;
    }
}
