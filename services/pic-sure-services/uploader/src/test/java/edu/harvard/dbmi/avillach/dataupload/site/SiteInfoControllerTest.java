package edu.harvard.dbmi.avillach.dataupload.site;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

@SpringBootTest
class SiteInfoControllerTest {

    @Mock
    SiteListing listing;

    @InjectMocks
    SiteInfoController subject;

    @Test
    void shouldListSites() {
        ResponseEntity<SiteListing> actual = subject.listSites();

        Assertions.assertEquals(listing, actual.getBody());
        Assertions.assertTrue(actual.getStatusCode().is2xxSuccessful());
    }
}