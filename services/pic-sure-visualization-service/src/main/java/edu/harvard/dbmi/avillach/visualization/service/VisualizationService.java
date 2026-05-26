package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.HpdsUpstreamException;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.*;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalDistributionProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ContinuousDistributionProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.ArrayList;
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
        return generateDistributions(query, accessContext, bearerToken, null);
    }

    public VisualizationResponse generateDistributions(Query query, HpdsAccessContext accessContext, String bearerToken, String requestId) {
        List<QueryDecomposer.SubQueryDescriptor> subQueries = queryDecomposer.decompose(query);
        List<CategoricalDistributionData> categoricalData = new ArrayList<>();
        List<ContinuousDistributionData> continuousData = new ArrayList<>();
        logger.info(
            "Generating visualization distributions requestId={} accessType={} resourceUUID={} subQueryCount={}",
            requestId, accessContext.accessType().getValue(), accessContext.resourceUUID(), subQueries.size()
        );

        for (QueryDecomposer.SubQueryDescriptor descriptor : subQueries) {
            try {
                if (accessContext.accessType() == AccessType.AUTHORIZED) {
                    Map<String, Map<String, Integer>> crossCounts = requestId == null
                        ? hpdsClient
                            .getAuthCrossCounts(descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken)
                        : hpdsClient.getAuthCrossCounts(
                            descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken, requestId,
                            accessContext.accessType(), descriptor.distributionKind()
                        );
                    addDistributions(
                        descriptor, crossCounts, DistributionProcessingOptions.AUTHORIZED, false, categoricalData, continuousData
                    );
                } else {
                    Map<String, Map<String, String>> rawCrossCounts = requestId == null
                        ? hpdsClient
                            .getOpenCrossCounts(descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken)
                        : hpdsClient.getOpenCrossCounts(
                            descriptor.query(), descriptor.resultType(), accessContext.resourceUUID(), bearerToken, requestId,
                            accessContext.accessType(), descriptor.distributionKind()
                        );
                    if (hasSeriesData(rawCrossCounts)) {
                        boolean isObfuscated = obfuscationParser.isObfuscated(rawCrossCounts);
                        Map<String, Map<String, Integer>> cleanedCounts = obfuscationParser.clean(rawCrossCounts);
                        if (hasSeriesData(cleanedCounts)) {
                            addDistributions(
                                descriptor, cleanedCounts, DistributionProcessingOptions.OPEN, isObfuscated, categoricalData, continuousData
                            );
                        }
                    }
                }
            } catch (HttpStatusCodeException e) {
                logger.error(
                    "HPDS returned HTTP {} for {} {} query", e.getStatusCode().value(), accessContext.accessType().getValue(),
                    descriptor.resultType(), e
                );
                throw new HpdsUpstreamException("HPDS query failed with status " + e.getStatusCode().value() + ": " + e.getStatusText(), e);
            } catch (ResourceAccessException e) {
                logger.error("Could not reach HPDS for {} {} query", accessContext.accessType().getValue(), descriptor.resultType(), e);
                throw new HpdsUpstreamException("HPDS query failed: " + e.getMessage(), e);
            } catch (VisualizationException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to execute {} {} query", accessContext.accessType().getValue(), descriptor.resultType(), e);
                throw new HpdsUpstreamException("HPDS query failed: " + e.getMessage(), e);
            }
        }

        VisualizationResponse response = new VisualizationResponse(categoricalData, continuousData);
        logger.info(
            "Generated visualization distributions requestId={} accessType={} categoricalChartCount={} continuousChartCount={}",
            requestId, accessContext.accessType().getValue(), categoricalData.size(), continuousData.size()
        );
        return response;
    }

    public int subQueryCount(Query query) {
        return queryDecomposer.decompose(query).size();
    }

    private void addDistributions(
        QueryDecomposer.SubQueryDescriptor descriptor, Map<String, Map<String, Integer>> crossCounts, DistributionProcessingOptions options,
        boolean isObfuscated, List<CategoricalDistributionData> categoricalData, List<ContinuousDistributionData> continuousData
    ) {
        if (!hasSeriesData(crossCounts)) {
            return;
        }

        if (descriptor.distributionKind() == DistributionType.CATEGORICAL) {
            List<CategoricalDistributionData> charts = categoricalDistributionProcessor
                .process(crossCounts, isObfuscated, options.aggregateCategoricalValues());
            categoricalData.addAll(charts);
            logCreatedCharts(descriptor, charts.size(), crossCounts, isObfuscated);
        } else {
            List<ContinuousDistributionData> charts = continuousDistributionProcessor
                .process(crossCounts, isObfuscated, options.binContinuousValues());
            continuousData.addAll(charts);
            logCreatedCharts(descriptor, charts.size(), crossCounts, isObfuscated);
        }
    }

    private static void logCreatedCharts(
        QueryDecomposer.SubQueryDescriptor descriptor, int chartCount, Map<String, Map<String, Integer>> crossCounts, boolean isObfuscated
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
