package edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class TimeSeriesConversionServiceTest {

    TimeSeriesConversionService subject = new TimeSeriesConversionService();

    @Test
    public void shouldConvertToIsoString() {
        String actual = subject.toISOString(0L);
        String expected = "1970-01-01T00:00:00Z";

        assertEquals(expected, actual);
    }
}