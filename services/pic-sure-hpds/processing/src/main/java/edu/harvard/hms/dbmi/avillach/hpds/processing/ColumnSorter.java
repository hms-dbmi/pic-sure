package edu.harvard.hms.dbmi.avillach.hpds.processing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ColumnSorter {
    private final Map<String, Integer> infoColumnsOrder;

    @Autowired
    public ColumnSorter(@Value("#{'${variant.info_column_order:}'}") String infoColumnOrderString) {
        if (infoColumnOrderString == null || infoColumnOrderString.isEmpty()) {
            infoColumnsOrder = Map.of();
        } else {
            String[] infoColumnOrder = infoColumnOrderString.split(",");
            HashMap<String, Integer> order = new HashMap<>();
            for (int i = 0; i < infoColumnOrder.length; i++) {
                order.put(infoColumnOrder[i], i);
            }
            this.infoColumnsOrder = order;
        }
    }

    public List<String> sortInfoColumns(Set<String> columns) {
        // backwards compatibility check.
        if (infoColumnsOrder.isEmpty()) {
            return new ArrayList<>(columns);
        }
        return columns.stream()
            .filter(infoColumnsOrder::containsKey)
            .sorted((a, b) -> Integer.compare(
                infoColumnsOrder.getOrDefault(a, Integer.MAX_VALUE),
                infoColumnsOrder.getOrDefault(b, Integer.MAX_VALUE)
            ))
            .collect(Collectors.toList());
    }
}
