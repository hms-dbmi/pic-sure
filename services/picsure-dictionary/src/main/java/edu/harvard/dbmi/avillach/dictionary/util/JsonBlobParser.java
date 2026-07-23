package edu.harvard.dbmi.avillach.dictionary.util;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JsonBlobParser {

    private final static Logger log = LoggerFactory.getLogger(JsonBlobParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonBlobParser() {}

    public List<String> parseValues(String valuesArr) {
        try {
            JsonNode arr = objectMapper.readTree(valuesArr);
            if (!arr.isArray()) {
                return List.of();
            }
            ArrayList<String> vals = new ArrayList<>();
            for (JsonNode node : arr) {
                if (!node.isTextual()) {
                    return List.of();
                }
                vals.add(node.asText());
            }
            return vals;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public Double parseMin(String valuesArr) {
        return parseFromIndex(valuesArr, 0);
    }

    protected Double parseFromIndex(String valuesArr, int index) {
        try {
            JsonNode arr = objectMapper.readTree(valuesArr);
            if (!arr.isArray() || arr.size() != 2) {
                return 0D;
            }
            JsonNode raw = arr.get(index);
            if (raw.isNumber()) {
                return raw.doubleValue();
            }
            if (raw.isTextual()) {
                return Double.parseDouble(raw.asText());
            }
            return 0D;
        } catch (JsonProcessingException ex) {
            log.warn("Invalid json array for values: ", ex);
            return 0D;
        } catch (NumberFormatException ex) {
            log.warn("Valid json array but invalid val within: ", ex);
            return 0D;
        }
    }

    public Double parseMax(String valuesArr) {
        return parseFromIndex(valuesArr, 1);
    }

    public Map<String, String> parseMetaData(String jsonMetaData) {
        Map<String, String> metadata;

        try {
            List<Map<String, String>> maps = objectMapper.readValue(jsonMetaData, new TypeReference<List<Map<String, String>>>() {});
            // convert the list to a flat map
            Map<String, String> map = new HashMap<>();
            for (Map<String, String> entry : maps) {
                String rawKey = entry.get("key");
                if (rawKey == null || rawKey.isBlank()) {
                    throw new IllegalStateException("parseMetaData() Missing metadata key. Entry: " + entry);
                }
                String prettyKey = Arrays.stream(rawKey.split("_")).filter(word -> !word.isBlank())
                    .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase()).collect(Collectors.joining(" "));
                if (map.put(prettyKey, entry.get("value")) != null) {
                    throw new IllegalStateException(
                        "parseMetaData() Duplicate key found in metadata. Key: " + prettyKey + "(" + entry.get("key") + ") Value: "
                            + entry.get("value")
                    );
                }
            }
            metadata = map;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return metadata;
    }
}
