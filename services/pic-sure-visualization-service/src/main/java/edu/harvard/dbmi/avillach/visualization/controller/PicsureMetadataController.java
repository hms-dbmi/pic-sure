package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PicsureMetadataController {

    @PostMapping("/query/format")
    public ResponseEntity<QueryFormat> queryFormat() {
        return ResponseEntity.ok(distributionQueryFormat());
    }

    private QueryFormat distributionQueryFormat() {
        return new QueryFormat().setName("PIC-SURE Visualization Distributions")
            .setDescription("Request format for POST /{backend}/distributions")
            .setSpecification(Map.of("query", "PIC-SURE HPDS v3 query used to generate distribution charts"));
    }
}
