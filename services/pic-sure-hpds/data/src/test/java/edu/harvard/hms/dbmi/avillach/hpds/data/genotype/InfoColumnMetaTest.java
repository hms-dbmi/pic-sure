package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InfoColumnMetaTest {

    @Test
    public void jacksonSerializesAndDeserializes() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        InfoColumnMeta infoColumnMeta = new InfoColumnMeta("abc123", "the description", true, 1.0f, 20.0f);

        String serialized = objectMapper.writeValueAsString(infoColumnMeta);

        InfoColumnMeta deserialized = objectMapper.readValue(serialized, InfoColumnMeta.class);

        assertEquals(infoColumnMeta, deserialized);
    }
}
