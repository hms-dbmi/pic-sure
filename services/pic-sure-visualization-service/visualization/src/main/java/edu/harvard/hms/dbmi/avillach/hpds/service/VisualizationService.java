package edu.harvard.hms.dbmi.avillach.hpds.service;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.internal.chartpart.Chart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;

@Service
public class VisualizationService implements IVisualizationService {

    private Logger logger = LoggerFactory.getLogger(VisualizationService.class);

    public String createBase64PNG(Chart chart) {
        try {
            byte[] bytes = BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            logger.error("Error creating base64 PNG", e);
            return null;
        }
    }
}
