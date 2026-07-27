package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.HpdsUpstreamException;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.*;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalAggregationService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalDistributionProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ContinuousDistributionProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class VisualizationService {

    private static final Logger logger = LoggerFactory.getLogger(VisualizationService.class);

    private final QueryDecomposer queryDecomposer;
    private final QueryServiceClient queryServiceClient;
    private final CategoricalDistributionProcessor categoricalDistributionProcessor;
    private final ContinuousDistributionProcessor continuousDistributionProcessor;
    private final BinningService binningService;
    private final CategoricalAggregationService categoricalAggregationService;

    public VisualizationService(
        QueryDecomposer queryDecomposer, QueryServiceClient queryServiceClient,
        CategoricalDistributionProcessor categoricalDistributionProcessor, ContinuousDistributionProcessor continuousDistributionProcessor,
        BinningService binningService, CategoricalAggregationService categoricalAggregationService
    ) {
        this.queryDecomposer = queryDecomposer;
        this.queryServiceClient = queryServiceClient;
        this.categoricalDistributionProcessor = categoricalDistributionProcessor;
        this.continuousDistributionProcessor = continuousDistributionProcessor;
        this.binningService = binningService;
        this.categoricalAggregationService = categoricalAggregationService;
    }

    public VisualizationResponse generateDistributions(
        Query query, AccessType accessType, QueryServiceClient.GatewayIdentity identity, String requestId
    ) {
        List<QueryDecomposer.SubQueryDescriptor> subQueries = queryDecomposer.decompose(query);
        List<CategoricalDistributionData> categoricalData = new ArrayList<>();
        List<ContinuousDistributionData> continuousData = new ArrayList<>();
        logger.info(
            "Generating visualization distributions requestId={} accessType={} subQueryCount={}", requestId, accessType.getValue(),
            subQueries.size()
        );

        for (QueryDecomposer.SubQueryDescriptor descriptor : subQueries) {
            try {
                if (accessType == AccessType.AUTHORIZED) {
                    Map<String, Map<String, Integer>> crossCounts = queryServiceClient.getAuthCrossCounts(
                        descriptor.query(), descriptor.resultType(), identity, requestId, descriptor.distributionKind()
                    );
                    logRawHpdsShape(descriptor, crossCounts);

                    Map<String, Map<String, Integer>> processed = new LinkedHashMap<>();
                    switch (descriptor.distributionKind()) {
                        case CONTINUOUS -> crossCounts
                            .forEach((concept, values) -> processed.put(concept, binningService.bucketData(nonNullValues(values))));
                        case CATEGORICAL -> crossCounts.forEach(
                            (concept, values) -> processed.put(concept, categoricalAggregationService.aggregateTopN(nonNullValues(values)))
                        );
                    }
                    addDistributions(descriptor, wrap(processed), false, categoricalData, continuousData);
                } else {
                    Map<String, Map<String, ObfuscatedCount>> rawCrossCounts = queryServiceClient.getOpenCrossCounts(
                        descriptor.query(), descriptor.resultType(), identity, requestId, descriptor.distributionKind()
                    );
                    logRawHpdsShape(descriptor, rawCrossCounts);
                    if (hasSeriesData(rawCrossCounts)) {
                        addDistributions(descriptor, rawCrossCounts, true, categoricalData, continuousData);
                    }
                }
            } catch (HttpStatusCodeException e) {
                logger.error(
                    "Query service returned HTTP {} for {} {} query", e.getStatusCode().value(), accessType.getValue(),
                    descriptor.resultType(), e
                );
                throw new HpdsUpstreamException(
                    "Query service request failed with status " + e.getStatusCode().value() + ": " + e.getStatusText(), e
                );
            } catch (ResourceAccessException e) {
                logger.error("Could not reach query service for {} {} query", accessType.getValue(), descriptor.resultType(), e);
                throw new HpdsUpstreamException("Query service request failed: " + e.getMessage(), e);
            } catch (VisualizationException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to execute {} {} query", accessType.getValue(), descriptor.resultType(), e);
                throw new HpdsUpstreamException("Query service request failed: " + e.getMessage(), e);
            }
        }

        VisualizationResponse response = new VisualizationResponse(categoricalData, continuousData);
        logger.info(
            "Generated visualization distributions requestId={} accessType={} categoricalChartCount={} continuousChartCount={}", requestId,
            accessType.getValue(), categoricalData.size(), continuousData.size()
        );
        return response;
    }

    public int subQueryCount(Query query) {
        return queryDecomposer.decompose(query).size();
    }

    private static Map<String, Integer> nonNullValues(Map<String, Integer> values) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        values.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, Map<String, ObfuscatedCount>> wrap(Map<String, Map<String, Integer>> input) {
        Map<String, Map<String, ObfuscatedCount>> out = new LinkedHashMap<>();
        input.forEach((concept, values) -> {
            Map<String, ObfuscatedCount> wrappedValues = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((k, v) -> {
                    if (v != null) {
                        wrappedValues.put(k, ObfuscatedCount.ofInt(v));
                    }
                });
            }
            out.put(concept, wrappedValues);
        });
        return out;
    }

    private void addDistributions(
        QueryDecomposer.SubQueryDescriptor descriptor, Map<String, Map<String, ObfuscatedCount>> crossCounts, boolean isObfuscated,
        List<CategoricalDistributionData> categoricalData, List<ContinuousDistributionData> continuousData
    ) {
        if (!hasSeriesData(crossCounts)) {
            return;
        }
        switch (descriptor.distributionKind()) {
            case CATEGORICAL -> {
                List<CategoricalDistributionData> charts = categoricalDistributionProcessor.process(crossCounts, isObfuscated);
                categoricalData.addAll(charts);
                logCreatedCharts(descriptor, charts.size(), crossCounts, isObfuscated);
            }
            case CONTINUOUS -> {
                List<ContinuousDistributionData> charts = continuousDistributionProcessor.process(crossCounts, isObfuscated);
                continuousData.addAll(charts);
                logCreatedCharts(descriptor, charts.size(), crossCounts, isObfuscated);
            }
        }
    }

    private static void logRawHpdsShape(QueryDecomposer.SubQueryDescriptor descriptor, Map<String, ? extends Map<?, ?>> rawCrossCounts) {
        if (rawCrossCounts == null) {
            return;
        }
        logger.info(
            "HPDS cross-counts received distributionKind={} resultType={} rawSeriesCount={} rawPointCount={} rawSeriesKeys={}",
            descriptor.distributionKind().name().toLowerCase(), descriptor.resultType(), rawCrossCounts.size(),
            sourcePointCount(rawCrossCounts), new ArrayList<>(rawCrossCounts.keySet())
        );
    }

    private static void logCreatedCharts(
        QueryDecomposer.SubQueryDescriptor descriptor, int chartCount, Map<String, Map<String, ObfuscatedCount>> crossCounts,
        boolean isObfuscated
    ) {
        logger.info(
            "Created visualization charts distributionKind={} resultType={} chartCount={} sourceSeriesCount={} sourcePointCount={} sourceSeriesKeys={} obfuscated={}",
            descriptor.distributionKind().name().toLowerCase(), descriptor.resultType(), chartCount, crossCounts.size(),
            sourcePointCount(crossCounts), new ArrayList<>(crossCounts.keySet()), isObfuscated
        );
    }

    private static boolean hasSeriesData(Map<String, ? extends Map<?, ?>> crossCounts) {
        return (crossCounts != null && crossCounts.values().stream().anyMatch(values -> values != null && !values.isEmpty()));
    }

    private static int sourcePointCount(Map<String, ? extends Map<?, ?>> crossCounts) {
        int count = 0;
        for (Map<?, ?> values : crossCounts.values()) {
            if (values != null) {
                count += values.size();
            }
        }
        return count;
    }

    public Map<String, Map<String, Integer>> binContinuousData(Map<String, Map<String, Integer>> continuousData) {
        return binningService.binContinuousData(continuousData);
    }
}
