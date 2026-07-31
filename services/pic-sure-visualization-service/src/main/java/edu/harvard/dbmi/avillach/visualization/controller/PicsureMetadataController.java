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

    /**
     * Describes the BARE v3 query the endpoint binds -- its own fields, not a {@code query} wrapper around it. Advertising the wrapper
     * would tell clients to send a body {@code POST /distributions} rejects, and one the gateway's consent mutation strips off in any case.
     */
    private QueryFormat distributionQueryFormat() {
        return new QueryFormat(
            "PIC-SURE Visualization Distributions", "Request format for POST /distributions",
            Map.of(
                "select", "Concept paths to chart when no phenotypic filter names one", "phenotypicClause",
                "Phenotypic filter or subquery; its concept paths are what the distributions are computed over", "genomicFilters",
                "Genomic filters narrowing the cohort", "expectedResultType",
                "Ignored: each generated sub-query sets CATEGORICAL_CROSS_COUNT or CONTINUOUS_CROSS_COUNT itself"
            ), List.of()
        );
    }
}
