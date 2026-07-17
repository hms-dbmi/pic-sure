package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Site;
import edu.harvard.dbmi.avillach.data.repository.SiteRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
public class SiteParsingServiceTest {

    @InjectMocks
    private SiteParsingService subject;

    @Mock
    private SiteRepository repository;

    @Test
    public void shouldParse() {
        Site site = new Site();
        site.setCode("BCH");
        site.setName("Bowston Children's Hospital");
        site.setDomain("childrens.harvard.edu");
        Mockito.when(repository.getByColumn("domain", "childrens.harvard.edu")).thenReturn(List.of(site));

        Optional<String> actual = subject.parseSiteOfOrigin("aaaaaaah@childrens.harvard.edu");
        Optional<String> expected = Optional.of("BCH");

        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void shouldFailWhenNoSite() {
        Mockito.when(repository.getByColumn("domain", "childrens.harvard.edu")).thenReturn(List.of());

        Optional<String> actual = subject.parseSiteOfOrigin("aaaaaaah@childrens.harvard.edu");
        Optional<String> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void shouldFailWhenManySites() {
        Site siteA = new Site();
        siteA.setCode("BCH");
        siteA.setName("Bowston Children's Hospital");
        siteA.setDomain("edu");
        Site siteB = new Site();
        siteB.setCode("CHOP");
        siteB.setName("Children's Hospital of Philly");
        siteB.setDomain("edu");
        Mockito.when(repository.getByColumn("domain", "edu")).thenReturn(List.of(siteA, siteB));

        Optional<String> actual = subject.parseSiteOfOrigin("aaaaaaah@edu");
        Optional<String> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }
}
