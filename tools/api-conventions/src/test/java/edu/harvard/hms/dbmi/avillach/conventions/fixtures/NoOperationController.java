package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** One handler with no Operation and one with a blank summary, so both violate R3. */
@Tag(name = "No operation", description = "Handlers missing their operation metadata")
@RestController
public class NoOperationController {

    @ApiResponse(responseCode = "200", description = "The thing")
    @GetMapping("/no-operation")
    public String read() {
        return "";
    }

    @Operation(summary = "  ")
    @ApiResponse(responseCode = "200", description = "The thing")
    @PostMapping("/blank-summary")
    public String create() {
        return "";
    }
}
