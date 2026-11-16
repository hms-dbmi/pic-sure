package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportRequest;
import edu.harvard.dbmi.avillach.dictionary.dataset.DatasetService;

/**
 * Pins how {@link ConceptService} turns the configured export keys into its allowlist: blank entries are dropped and surrounding whitespace
 * is stripped from the rest.
 */
@ExtendWith(MockitoExtension.class)
class ConceptServiceExportKeysTest {

    @Mock
    private ConceptRepository conceptRepository;

    @Mock
    private DatasetService datasetService;

    @Mock
    private ConceptDecoratorService conceptDecoratorService;

    private ConceptService serviceAllowing(Set<String> configuredKeys) {
        return new ConceptService(conceptRepository, datasetService, conceptDecoratorService, configuredKeys);
    }

    @Test
    void blankConfiguredEntriesDoNotAllowABlankKey() {
        ConceptService service = serviceAllowing(Set.of("", " ", "drs_uri"));

        for (String blankKey : List.of("", " ")) {
            assertThrows(
                InvalidConceptExportRequestException.class,
                () -> service.exportMetadata(new ConceptExportRequest(List.of("a"), List.of(blankKey))), "'" + blankKey + "'"
            );
        }
        verifyNoInteractions(conceptRepository);
    }

    @Test
    void whitespaceAroundAConfiguredEntryIsStripped() {
        ConceptService service = serviceAllowing(Set.of(" drs_uri "));
        when(conceptRepository.getExportMetadata(List.of("a"), List.of("drs_uri"))).thenReturn(List.of());

        assertEquals(List.of(), service.exportMetadata(new ConceptExportRequest(List.of("a"), List.of("drs_uri"))));

        verify(conceptRepository).getExportMetadata(List.of("a"), List.of("drs_uri"));
    }
}
