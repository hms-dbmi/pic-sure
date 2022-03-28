package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

import java.awt.*;
import java.util.List;

@Data
public class VisualizationResponse {
    private List<VisualizationImage> images;

    public List<VisualizationImage> getImages() {
        if (images == null) {
            images = new java.util.ArrayList<>();
        }
        return images;
    }
}
