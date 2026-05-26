package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.*;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.ChartProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ChartProcessorRegistry;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class VisualizationService {

    private static final Logger logger = LoggerFactory.getLogger(VisualizationService.class);

    private final QueryDecomposer queryDecomposer;
    private final HpdsClient hpdsClient;
    private final ObfuscationParser obfuscationParser;
    private final ChartProcessorRegistry chartProcessorRegistry;
    private final BinningService binningService;

    public VisualizationService(
        QueryDecomposer queryDecomposer, HpdsClient hpdsClient, ObfuscationParser obfuscationParser,
        ChartProcessorRegistry chartProcessorRegistry, BinningService binningService
    ) {
        this.queryDecomposer = queryDecomposer;
        this.hpdsClient = hpdsClient;
        this.obfuscationParser = obfuscationParser;
        this.chartProcessorRegistry = chartProcessorRegistry;
        this.binningService = binningService;
    }

    public VisualizationResponse handleQuerySync(Query query, AccessType accessType, String bearerToken) {
        List<QueryDecomposer.SubQueryDescriptor> subQueries = queryDecomposer.decompose(query);
        List<ChartData> allCharts = new ArrayList<>();

        for (QueryDecomposer.SubQueryDescriptor descriptor : subQueries) {
            ChartType chartType = "bar".equals(descriptor.chartType()) ? ChartType.BAR : ChartType.HISTOGRAM;
            ChartProcessor processor = chartProcessorRegistry.get(chartType);

            try {
                if (accessType == AccessType.AUTHORIZED) {
                    Map<String, Map<String, Integer>> crossCounts =
                        hpdsClient.getAuthCrossCounts(descriptor.query(), descriptor.resultType(), bearerToken);
                    if (crossCounts != null && !crossCounts.isEmpty()) {
                        Map<String, Map<String, Integer>> processed = processor.preProcess(crossCounts);
                        allCharts.addAll(processor.process(processed, false));
                    }
                } else {
                    Map<String, Map<String, String>> rawCrossCounts =
                        hpdsClient.getOpenCrossCounts(descriptor.query(), descriptor.resultType(), bearerToken);
                    if (rawCrossCounts != null && !rawCrossCounts.isEmpty()) {
                        boolean isObfuscated = obfuscationParser.isObfuscated(rawCrossCounts);
                        Map<String, Map<String, Integer>> cleanedCounts = obfuscationParser.clean(rawCrossCounts);
                        allCharts.addAll(processor.process(cleanedCounts, isObfuscated));
                    }
                }
            } catch (HttpStatusCodeException e) {
                logger.error(
                    "HPDS returned HTTP {} for {} {} query", e.getStatusCode().value(), accessType.getValue(), descriptor.resultType(), e
                );
                throw new VisualizationException(
                    "HPDS query failed with status " + e.getStatusCode().value() + ": " + e.getStatusText(), e
                );
            } catch (VisualizationException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to execute {} {} query", accessType.getValue(), descriptor.resultType(), e);
                throw new VisualizationException("HPDS query failed: " + e.getMessage(), e);
            }
        }

        return new VisualizationResponse(allCharts);
    }

    public Map<String, Map<String, Integer>> binContinuousData(Map<String, Map<String, Integer>> continuousData) {
        return binningService.binContinuousData(continuousData);
    }
}
