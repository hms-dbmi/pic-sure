package edu.harvard.hms.dbmi.avillach.hpds.model.domain;

import edu.harvard.hms.dbmi.avillach.hpds.model.PicSureSeriesColors;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.colors.GGPlot2SeriesColors;
import org.knowm.xchart.style.theme.GGPlot2Theme;
import org.knowm.xchart.style.theme.MatlabTheme;
import org.knowm.xchart.style.theme.Theme;
import org.knowm.xchart.style.theme.XChartTheme;

import java.awt.*;

public class PicSureTheme extends GGPlot2Theme {
    @Override
    public Color[] getSeriesColors() {
        return (new PicSureSeriesColors(null)).getSeriesColors();
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

