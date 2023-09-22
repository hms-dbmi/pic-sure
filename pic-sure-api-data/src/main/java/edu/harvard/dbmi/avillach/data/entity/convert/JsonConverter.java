package edu.harvard.dbmi.avillach.data.entity.convert;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.AttributeConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonConverter implements AttributeConverter<Map<String, Object>, String> {
    private final Logger logger = LoggerFactory.getLogger(JsonConverter.class);

    @Override
    public String convertToDatabaseColumn(Map<String, Object> objectData) {
        if (objectData == null) {
            return "{}";
        }

        String jsonData = null;
        try {
            jsonData = new ObjectMapper().writeValueAsString(objectData);
        } catch (final JsonProcessingException e) {
            logger.error("JSON writing error", e);
        }

        return jsonData;
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String jsonData) {
        if (jsonData == null) {
            return new HashMap<String, Object>();
        }

        Map<String, Object> objectData = null;
        try {
            objectData = new ObjectMapper().readValue(jsonData, new TypeReference<HashMap<String, Object>>() {});
        } catch (final IOException e) {
            logger.error("JSON reading error", e);
        }

        return objectData;
    }
}