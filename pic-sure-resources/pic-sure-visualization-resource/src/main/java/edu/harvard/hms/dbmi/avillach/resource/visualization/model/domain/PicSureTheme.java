package edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain;

import edu.harvard.hms.dbmi.avillach.resource.visualization.model.PicSureSeriesColors;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.theme.GGPlot2Theme;
import org.knowm.xchart.style.theme.Theme;

import java.awt.*;

public class PicSureTheme extends GGPlot2Theme {
    @Override
    public Color[] getSeriesColors() {
        return (new PicSureSeriesColors(null)).getSeriesColors();
    }

    @Override
    public Color getChartBackgroundColor() {
        return new Color(255, 255, 255);
    }

    @Override
    public Color getPlotBackgroundColor() {
        return new Color(255, 255, 255);
    }

    @Override
    public Color getPlotBorderColor() {
        return new Color(0, 0, 0);
    }

    public enum ChartTheme {
        PICSURE,
        BDC,
        GIC;

        private ChartTheme() {
        }

        public Theme newInstance(Styler.ChartTheme chartTheme) {
            return  new PicSureTheme();
//            switch(chartTheme) {
//                case PICSURE:
//                    return new PicSureTheme();
//                case BDC:
//                    return new PicSureTheme();
//                case GIC:
//                default:
//                    return new PicSureTheme();
//            }
        }
    }

}

