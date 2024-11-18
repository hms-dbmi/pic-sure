package edu.harvard.dbmi.avillach.dictionary.util;


import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Component
public class JsonBlobParser {

    private final static Logger log = LoggerFactory.getLogger(JsonBlobParser.class);

    public List<String> parseValues(String valuesArr) {
        try {
            ArrayList<String> vals = new ArrayList<>();
            JSONArray arr = new JSONArray(valuesArr);
            for (int i = 0; i < arr.length(); i++) {
                vals.add(arr.getString(i));
            }
            return vals;
        } catch (JSONException ex) {
            return List.of();
        }
    }

    public Float parseMin(String valuesArr) {
        return parseFromIndex(valuesArr, 0);
    }

    private Float parseFromIndex(String valuesArr, int index) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            if (arr.length() != 2) {
                return 0F;
            }
            Object raw = arr.get(index);
            return switch (raw) {
                case Double d -> d.floatValue();
                case Integer i -> i.floatValue();
                case String s -> Double.valueOf(s).floatValue();
                case BigDecimal d -> d.floatValue();
                case BigInteger i -> i.floatValue();
                default -> 0f;
            };
        } catch (JSONException ex) {
            log.warn("Invalid json array for values: ", ex);
            return 0F;
        } catch (NumberFormatException ex) {
            log.warn("Valid json array but invalid val within: ", ex);
            return 0F;
        }
    }

    public Float parseMax(String valuesArr) {
        return parseFromIndex(valuesArr, 1);
    }

}
