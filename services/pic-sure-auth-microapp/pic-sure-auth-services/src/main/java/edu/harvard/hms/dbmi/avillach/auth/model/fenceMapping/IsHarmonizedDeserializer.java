package edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class IsHarmonizedDeserializer extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        String text = jsonParser.getText();
        return "Y".equalsIgnoreCase(text) || "Yes".equalsIgnoreCase(text);
    }
}
