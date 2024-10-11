package edu.harvard.dbmi.avillach.dictionary.info;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;


@SpringBootTest
class InfoControllerTest {

    @Autowired
    InfoController infoController;

    @Test
    void shouldGetInfo() {
        ResponseEntity<InfoResponse> actual = infoController.getInfo(new Object());

        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
        Assertions.assertEquals(new InfoResponse(UUID.nameUUIDFromBytes(":)".getBytes()), ":)", List.of()), actual.getBody());
    }
}
