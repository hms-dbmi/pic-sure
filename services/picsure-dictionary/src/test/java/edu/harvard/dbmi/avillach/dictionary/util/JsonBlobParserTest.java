package edu.harvard.dbmi.avillach.dictionary.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JsonBlobParserTest {

    @Autowired
    private JsonBlobParser jsonBlobParser;

    @Test
    void parseValues() {
        Double v = jsonBlobParser.parseFromIndex("[-1.0,4.9E-324]", 1);
        assertNotNull(v);
        assertEquals(4.9E-324, v);

        v = jsonBlobParser.parseFromIndex("[-1.0,4.9E-324]", 0);
        assertNotNull(v);
        assertEquals(-1, v);
    }
}
