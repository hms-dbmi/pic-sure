package edu.harvard.dbmi.avillach.visualization.model;

public record DistributionProcessingOptions(boolean aggregateCategoricalValues, boolean binContinuousValues) {

    public static final DistributionProcessingOptions AUTHORIZED = new DistributionProcessingOptions(true, true);
    public static final DistributionProcessingOptions OPEN = new DistributionProcessingOptions(false, false);
}
