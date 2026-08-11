package edu.harvard.dbmi.avillach.dictionary.info;

import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;


@SpringBootTest
@ActiveProfiles("test")
class InfoControllerTest {

    @Autowired
    InfoController infoController;

    @Test
    void shouldGetInfo() {
        ResponseEntity<ResourceInfo> actual = infoController.getInfo();

        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
        Assertions.assertEquals(new ResourceInfo(UUID.nameUUIDFromBytes(":)".getBytes()), ":)", List.of()), actual.getBody());
    }
}
