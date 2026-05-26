package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.*;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalDistributionProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ContinuousDistributionProcessor;
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
    private final CategoricalDistributionProcessor categoricalDistributionProcessor;
    private final ContinuousDistributionProcessor continuousDistributionProcessor;
    private final BinningService binningService;

    public VisualizationService(
        QueryDecomposer queryDecomposer, HpdsClient hpdsClient, ObfuscationParser obfuscationParser,
        CategoricalDistributionProcessor categoricalDistributionProcessor, ContinuousDistributionProcessor continuousDistributionProcessor,
        BinningService binningService
    ) {
        this.queryDecomposer = queryDecomposer;
        this.hpdsClient = hpdsClient;
        this.obfuscationParser = obfuscationParser;
        this.categoricalDistributionProcessor = categoricalDistributionProcessor;
        this.continuousDistributionProcessor = continuousDistributionProcessor;
        this.binningService = binningService;
    }

    public VisualizationResponse generateDistributions(Query query, HpdsAccessContext accessContext, String bearerToken) {
        List<QueryDecomposer.SubQueryDescriptor> subQueries = queryDecomposer.decompose(query);
        List<CategoricalDistributionData> categoricalData = new ArrayList<>();
        List<ContinuousDistributionData> continuousData = new ArrayList<>();

        for (QueryDecomposer.SubQueryDescriptor descriptor : subQueries) {
            try {
                if (accessContext.accessType() == AccessType.AUTHORIZED) {
                    Map<String, Map<String, Integer>> crossCounts = hpdsClient
                        .getAuthCrossCounts(descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken);
                    addAuthorizedDistributions(descriptor, crossCounts, categoricalData, continuousData);
                } else {
                    Map<String, Map<String, String>> rawCrossCounts = hpdsClient
                        .getOpenCrossCounts(descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken);
                    if (hasSeriesData(rawCrossCounts)) {
                        boolean isObfuscated = obfuscationParser.isObfuscated(rawCrossCounts);
                        Map<String, Map<String, Integer>> cleanedCounts = obfuscationParser.clean(rawCrossCounts);
                        if (hasSeriesData(cleanedCounts)) {
                            addOpenDistributions(descriptor, cleanedCounts, isObfuscated, categoricalData, continuousData);
                        }
                    }
                }
            } catch (HttpStatusCodeException e) {
                logger.error(
                    "HPDS returned HTTP {} for {} {} query", e.getStatusCode().value(), accessContext.accessType().getValue(),
                    descriptor.resultType(), e
                );
                throw new VisualizationException(
                    "HPDS query failed with status " + e.getStatusCode().value() + ": " + e.getStatusText(), e
                );
            } catch (VisualizationException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to execute {} {} query", accessContext.accessType().getValue(), descriptor.resultType(), e);
                throw new VisualizationException("HPDS query failed: " + e.getMessage(), e);
            }
        }

        return new VisualizationResponse(categoricalData, continuousData);
    }

    private void addAuthorizedDistributions(
        QueryDecomposer.SubQueryDescriptor descriptor, Map<String, Map<String, Integer>> crossCounts,
        List<CategoricalDistributionData> categoricalData, List<ContinuousDistributionData> continuousData
    ) {
        if (!hasSeriesData(crossCounts)) {
            return;
        }

        if ("bar".equals(descriptor.chartType())) {
            categoricalData.addAll(categoricalDistributionProcessor.process(crossCounts, false, true));
        } else {
            continuousData.addAll(continuousDistributionProcessor.process(crossCounts, false, true));
        }
    }

    private void addOpenDistributions(
        QueryDecomposer.SubQueryDescriptor descriptor, Map<String, Map<String, Integer>> crossCounts, boolean isObfuscated,
        List<CategoricalDistributionData> categoricalData, List<ContinuousDistributionData> continuousData
    ) {
        if ("bar".equals(descriptor.chartType())) {
            categoricalData.addAll(categoricalDistributionProcessor.process(crossCounts, isObfuscated, false));
        } else {
            continuousData.addAll(continuousDistributionProcessor.process(crossCounts, isObfuscated, false));
        }
    }

    private static boolean hasSeriesData(Map<String, ? extends Map<?, ?>> crossCounts) {
        return crossCounts != null && crossCounts.values().stream().anyMatch(values -> values != null && !values.isEmpty());
    }

    public Map<String, Map<String, Integer>> binContinuousData(Map<String, Map<String, Integer>> continuousData) {
        return binningService.binContinuousData(continuousData);
    }
}
