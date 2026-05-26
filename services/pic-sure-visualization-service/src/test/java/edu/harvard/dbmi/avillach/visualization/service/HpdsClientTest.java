package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HpdsClientTest {

    @Test
    void getAuthCrossCounts_withConfiguredUUID_delegatesToStrategy() {
        HpdsCallStrategy strategy = mock(HpdsCallStrategy.class);
        Map<String, Map<String, Integer>> expected = Map.of("\\test\\", Map.of("a", 1));
        doReturn(expected).when(strategy).queryCrossCounts(any(), any(), any(), any(), any());

        HpdsClient client = new HpdsClient(strategy, "550e8400-e29b-41d4-a716-446655440000", "");

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, Integer>> result = client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, "Bearer token");

        assertEquals(expected, result);
        verify(strategy).queryCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), eq("Bearer token"), any());
    }

    @Test
    void getAuthCrossCounts_withNoUUID_throwsVisualizationException() {
        HpdsCallStrategy strategy = mock(HpdsCallStrategy.class);
        HpdsClient client = new HpdsClient(strategy, "", "");

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationException ex = assertThrows(
            VisualizationException.class, () -> client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, "Bearer token")
        );
        assertTrue(ex.getMessage().contains("hpds.resource.authorized.uuid"));
        verifyNoInteractions(strategy);
    }

    @Test
    void getOpenCrossCounts_fallsBackToAuthorizedUUID() {
        HpdsCallStrategy strategy = mock(HpdsCallStrategy.class);
        Map<String, Map<String, String>> expected = Map.of("\\test\\", Map.of("a", "1"));
        doReturn(expected).when(strategy).queryCrossCounts(any(), any(), any(), any(), any());

        HpdsClient client = new HpdsClient(strategy, "550e8400-e29b-41d4-a716-446655440000", "");

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, String>> result = client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, null);

        assertEquals(expected, result);
    }

    @Test
    void getOpenCrossCounts_withNoUUIDs_throwsVisualizationException() {
        HpdsCallStrategy strategy = mock(HpdsCallStrategy.class);
        HpdsClient client = new HpdsClient(strategy, "", "");

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationException ex =
            assertThrows(VisualizationException.class, () -> client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, null));
        assertTrue(ex.getMessage().contains("hpds.resource.open.uuid"));
        verifyNoInteractions(strategy);
    }
}
