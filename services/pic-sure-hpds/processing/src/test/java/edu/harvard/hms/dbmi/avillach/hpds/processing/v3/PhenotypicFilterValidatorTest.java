package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PhenotypicFilterValidatorTest {

    private PhenotypicFilterValidator phenotypicFilterValidator;

    private Map<String, ColumnMeta> metaStore;

    @BeforeEach
    public void setup() {
        phenotypicFilterValidator = new PhenotypicFilterValidator();
        metaStore = Map.of(
            "\\study123\\demographics\\sex\\", new ColumnMeta().setCategorical(true), "\\study123\\demographics\\age\\",
            new ColumnMeta().setCategorical(false).setMin(0).setMax(130)
        );
    }

    @Test
    public void validate_validCategoricalFilter_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\sex\\", List.of("male"), null, null, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_nonExistingCategoricalFilter_isValid() {
        PhenotypicFilter phenotypicFilter = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\study123\\demographics\\not_a_real_concept\\", List.of("male"), null, null, null
        );

        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_invalidCategoricalFilter_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", List.of("male"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));
    }

    @Test
    public void validate_validNumericFilter_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", null, 20.0, 25.0, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_validNumericFilterMinOnly_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", null, 20.0, null, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_validNumericFilterMaxOnly_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", null, null, 45.0, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_nonExistingNumericFilter_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\not_a_real_concept\\", null, 20.0, null, null);

        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_invalidNumericFilter_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\sex\\", null, null, 20.0, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));
    }

    @Test
    public void validate_invalidFilterValuesAndMinMax_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\sex\\", List.of("male"), null, 20.0, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));
    }

    @Test
    public void validate_anyRecordOfFilter_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\study123\\", null, null, null, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_anyRecordOfFilterWithValues_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\study123\\", List.of("purple"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));
        PhenotypicFilter phenotypicFilter2 =
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\study123\\", null, 42.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter2, metaStore));
    }


    @Test
    public void validate_requiredFilter_isValid() {
        PhenotypicFilter phenotypicFilter = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\study123\\", null, null, null, null);
        phenotypicFilterValidator.validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_requiredFilterWithValues_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\study123\\", List.of("purple"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));
        PhenotypicFilter phenotypicFilter2 = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\study123\\", null, 42.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> phenotypicFilterValidator.validate(phenotypicFilter2, metaStore));
    }

}
