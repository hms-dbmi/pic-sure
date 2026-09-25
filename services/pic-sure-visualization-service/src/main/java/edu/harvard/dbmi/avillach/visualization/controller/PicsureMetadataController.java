package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Resource metadata", description = "Query format for PIC-SURE clients")
public class PicsureMetadataController {

    @Operation(summary = "Describe the query format this resource accepts")
    @ApiResponse(responseCode = "200", description = "The query format specification")
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
