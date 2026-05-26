package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChartProcessorRegistry {

    private final Map<ChartType, ChartProcessor> processors;

    public ChartProcessorRegistry(List<ChartProcessor> processorList) {
        this.processors = processorList.stream().collect(Collectors.toMap(ChartProcessor::chartType, Function.identity()));
    }

    public ChartProcessor get(ChartType chartType) {
        ChartProcessor processor = processors.get(chartType);
        if (processor == null) {
            throw new IllegalArgumentException("No chart processor registered for type: " + chartType);
        }
        return processor;
    }
}
