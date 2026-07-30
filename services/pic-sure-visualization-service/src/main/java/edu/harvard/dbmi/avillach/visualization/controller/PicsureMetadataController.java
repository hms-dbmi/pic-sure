package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The PIC-SURE resource-metadata surface. Both endpoints answer with the shared {@code contracts.info} records, so viz, HPDS, dictionary
 * and logging describe themselves in ONE schema rather than three incompatible ones.
 *
 * <p>{@code id} is null: this service is not a registry resource and never was addressable by UUID -- the gateway routes to it by path.
 */
@RestController
public class PicsureMetadataController {

    @PostMapping("/info")
    public ResponseEntity<ResourceInfo> info() {
        return ResponseEntity.ok(new ResourceInfo(null, "PIC-SURE Visualization Service", List.of(distributionQueryFormat())));
    }

    @PostMapping("/query/format")
    public ResponseEntity<QueryFormat> queryFormat() {
        return ResponseEntity.ok(distributionQueryFormat());
    }

    private QueryFormat distributionQueryFormat() {
        return new QueryFormat(
            "PIC-SURE Visualization Distributions", "Request format for POST /distributions",
            Map.of("query", "PIC-SURE HPDS v3 query used to generate distribution charts"), List.of()
        );
    }
}
