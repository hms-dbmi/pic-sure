package edu.harvard.dbmi.avillach.infoservice;

import edu.harvard.dbmi.avillach.domain.ResourceInfo;
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

    @SuppressWarnings("DataFlowIssue")
    @Test
    void shouldDoHealthCheck() {
        ResponseEntity<ResourceInfo> actual = infoController.healthCheck(null);

        ResourceInfo expected = new ResourceInfo();
        expected.setName("Info Service");
        expected.setId(UUID.fromString("123e4567-e89b-42d3-a456-556642440000")); // from application.properties
        expected.setQueryFormats(List.of());

        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
        Assertions.assertEquals(expected.getName(), actual.getBody().getName());
        Assertions.assertEquals(expected.getId(), actual.getBody().getId());
        Assertions.assertEquals(expected.getQueryFormats(), actual.getBody().getQueryFormats());
    }
}