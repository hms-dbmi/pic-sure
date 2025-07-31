package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryValidatorTest {

    @Mock
    private PhenotypicQueryExecutor phenotypicQueryExecutor;

    @Mock
    private PhenotypicFilterValidator phenotypicFilterValidator;

    private QueryValidator queryValidator;

    private Map<String, ColumnMeta> metaStore;


    @BeforeEach
    public void setup() {
        metaStore = Map.of(
            "\\study123\\demographics\\sex\\", new ColumnMeta().setCategorical(true), "\\study123\\demographics\\age\\",
            new ColumnMeta().setCategorical(false).setMin(0).setMax(130)
        );

        queryValidator = new QueryValidator(phenotypicQueryExecutor, phenotypicFilterValidator);
        when(phenotypicQueryExecutor.getMetaStore()).thenReturn(metaStore);
    }

    @Test
    public void validate_emptyQuery_isValid() {
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);
        queryValidator.validate(query);
    }

    @Test
    public void validate_validPhenotypicFilter_isValid() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\sex\\", List.of("male"), null, null, null);
        Query query = new Query(List.of(), List.of(), phenotypicFilter, List.of(), ResultType.COUNT, null, null);
        queryValidator.validate(query);
        verify(phenotypicFilterValidator, times(1)).validate(phenotypicFilter, metaStore);
    }

    @Test
    public void validate_validNestedFilters_allValidated() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\sex\\", List.of("male"), null, null, null);
        PhenotypicFilter phenotypicFilter2 =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", null, 42.0, null, null);
        PhenotypicClause phenotypicSubquery1 = new PhenotypicSubquery(null, List.of(phenotypicFilter, phenotypicFilter2), Operator.AND);
        PhenotypicClause phenotypicSubquery2 = new PhenotypicSubquery(null, List.of(phenotypicSubquery1, phenotypicSubquery1), Operator.OR);
        Query query = new Query(List.of(), List.of(), phenotypicSubquery2, List.of(), ResultType.COUNT, null, null);
        queryValidator.validate(query);
        verify(phenotypicFilterValidator, times(2)).validate(phenotypicFilter, metaStore);
        verify(phenotypicFilterValidator, times(2)).validate(phenotypicFilter2, metaStore);
    }

    @Test
    public void validate_invalidFilter_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", List.of("male"), null, null, null);
        Query query = new Query(List.of(), List.of(), phenotypicFilter, List.of(), ResultType.COUNT, null, null);
        doThrow(IllegalArgumentException.class).when(phenotypicFilterValidator).validate(phenotypicFilter, metaStore);
        assertThrows(IllegalArgumentException.class, () -> queryValidator.validate(query));
    }

    @Test
    public void validate_invalidNnestedCategoricalFilter_throwsException() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", List.of("male"), null, null, null);
        PhenotypicFilter phenotypicFilter2 =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\study123\\demographics\\age\\", null, 42.0, null, null);
        PhenotypicClause phenotypicSubquery1 = new PhenotypicSubquery(null, List.of(phenotypicFilter, phenotypicFilter2), Operator.AND);
        PhenotypicClause phenotypicSubquery2 = new PhenotypicSubquery(null, List.of(phenotypicSubquery1, phenotypicSubquery1), Operator.OR);
        Query query = new Query(List.of(), List.of(), phenotypicSubquery2, List.of(), ResultType.COUNT, null, null);
        doThrow(IllegalArgumentException.class).when(phenotypicFilterValidator).validate(phenotypicFilter, metaStore);
        assertThrows(IllegalArgumentException.class, () -> queryValidator.validate(query));
    }
}
