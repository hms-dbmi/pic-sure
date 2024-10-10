package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import edu.harvard.dbmi.avillach.dictionary.dataset.DatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
public class ConceptDecoratorService {

    private static final Logger LOG = LoggerFactory.getLogger(ConceptDecoratorService.class);
    private final boolean enabled;
    private final ConceptService conceptService;
    private final DatasetService datasetService;

    private static final int COMPLIANT = 4, NON_COMPLIANT_TABLED = 3, NON_COMPLIANT_UNTABLED = 2;

    @Autowired
    public ConceptDecoratorService(
        @Value("${dashboard.enable.extra_details}") boolean enabled, @Lazy ConceptService conceptService, DatasetService datasetService // circular
                                                                                                                                        // dep
    ) {
        this.enabled = enabled;
        this.conceptService = conceptService;
        this.datasetService = datasetService;
    }


    public Concept populateParentConcepts(Concept concept) {
        if (!enabled) {
            return concept;
        }

        // In some environments, certain parent concepts have critical details that we need to add to the detailed response
        List<String> conceptNodes = Stream.of(concept.conceptPath().split("\\\\")).filter(Predicate.not(String::isBlank)).toList(); // you
                                                                                                                                    // have
                                                                                                                                    // to
                                                                                                                                    // double
                                                                                                                                    // escape
                                                                                                                                    // the
                                                                                                                                    // slash.
                                                                                                                                    // Once
                                                                                                                                    // for
                                                                                                                                    // strings,
                                                                                                                                    // and
                                                                                                                                    // once
                                                                                                                                    // for
                                                                                                                                    // regex

        return switch (conceptNodes.size()) {
            case COMPLIANT, NON_COMPLIANT_TABLED -> populateTabledConcept(concept, conceptNodes);
            case NON_COMPLIANT_UNTABLED -> populateNonCompliantTabledConcept(concept, conceptNodes);
            default -> {
                LOG.warn("Ignoring decoration request for weird concept path {}", concept.conceptPath());
                yield concept;
            }
        };
    }

    private Concept populateTabledConcept(Concept concept, List<String> conceptNodes) {
        String tablePath = "\\" + String.join("\\", conceptNodes.subList(0, 2)) + "\\";
        Dataset study = datasetService.getDataset(concept.dataset()).orElse(null);
        Concept table = conceptService.conceptDetailWithoutAncestors(concept.dataset(), tablePath).orElse(null);
        return concept.withStudy(study).withTable(table);
    }

    private Concept populateNonCompliantTabledConcept(Concept concept, List<String> conceptNodes) {
        Dataset study = datasetService.getDataset(concept.dataset()).orElse(null);
        return concept.withStudy(study);
    }
}
