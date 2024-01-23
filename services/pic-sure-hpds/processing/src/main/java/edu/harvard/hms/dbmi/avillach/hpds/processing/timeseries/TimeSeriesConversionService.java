package edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Service
public class TimeSeriesConversionService {

    public String toISOString(Long unixTimeStamp) {
        Instant instant = Instant.ofEpochMilli(unixTimeStamp);
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
