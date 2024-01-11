package edu.harvard.dbmi.avillach.dataupload.site;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

@SpringBootTest
class SiteConfigurationTest {

    @Mock
    ConfigurableApplicationContext context;

    @InjectMocks
    SiteConfiguration subject;

    @Test
    void shouldGetSiteInfo() {
        ReflectionTestUtils.setField(subject, "allSites", List.of("cchmc", "bch"));
        ReflectionTestUtils.setField(subject, "home", "bch");
        ReflectionTestUtils.setField(subject, "display", "BCH");

        SiteListing actual = subject.getSiteInfo();
        SiteListing expected = new SiteListing(List.of("bch", "cchmc"), "bch", "BCH");

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotGetSiteInfo() {
        ReflectionTestUtils.setField(subject, "allSites", List.of("cchmc", "narnia"));
        ReflectionTestUtils.setField(subject, "home", "bch");
        ReflectionTestUtils.setField(subject, "display", "BCH");

        subject.getSiteInfo();

        Mockito.verify(context, Mockito.times(1)).close();
    }
}