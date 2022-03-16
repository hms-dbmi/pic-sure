package edu.harvard.hms.dbmi.avillach.hpds.model;

import org.knowm.xchart.style.colors.GGPlot2SeriesColors;

import java.awt.*;

public class PicSureSeriesColors extends GGPlot2SeriesColors {

    private final Color[] seriesColors;

    public PicSureSeriesColors(Color[] seriesColors) {
        if (seriesColors == null || seriesColors.length == 0) {
            this.seriesColors = new Color[] {
                    new Color(0xFF0000),
                    new Color(0x00FF00),
                    new Color(0x0000FF),
                    new Color(0xFFFF00),
                    new Color(0xFF00FF),
                    new Color(0x00FFFF),
                    new Color(0x000000),
                    new Color(0xFFFFFF)
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
