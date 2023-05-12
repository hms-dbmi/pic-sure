package edu.harvard.hms.dbmi.avillach.resource.visualization.model;

import org.knowm.xchart.style.colors.GGPlot2SeriesColors;

import java.awt.*;

public class PicSureSeriesColors extends GGPlot2SeriesColors {

    private final Color[] seriesColors;

    public PicSureSeriesColors(Color[] seriesColors) {
        if (seriesColors == null || seriesColors.length == 0) {
            this.seriesColors = new Color[] {
                    new Color(0x1A568C),
                    new Color(0x41ABF5),
                    new Color(0x12385A),
                    new Color(0x616265),
                    new Color(0x393939),
                    Color.black
            };
        } else {
            this.seriesColors = seriesColors;
        }
    }

    @Override
    public Color[] getSeriesColors() {
        return this.seriesColors;
    }
}
