package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads a JSON string as a {@code String} and nothing else. Jackson's own {@code String} handling turns a number or boolean into text, so
 * {@code "select": [42]} would bind as the path {@code "42"} and pass validation. JSON null never reaches this class: Jackson binds it to
 * null before calling a deserializer.
 */
public class StrictStringDeserializer extends StdDeserializer<String> {

    public StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getText();
    }
}
