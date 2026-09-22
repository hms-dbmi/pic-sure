package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Four R4 shapes: none declared, a non-numeric code, a blank description, and errors with no success code. */
@Tag(name = "Bad responses", description = "Handlers whose responses fail the response rule")
@RestController
public class BadResponsesController {

    @Operation(summary = "Read the thing")
    @GetMapping("/none")
    public String none() {
        return "";
    }

    @Operation(summary = "Create the thing")
    @ApiResponse(responseCode = "okay", description = "The created thing")
    @PostMapping("/bad-code")
    public String badCode() {
        return "";
    }

    @Operation(summary = "Delete the thing")
    @ApiResponse(responseCode = "204", description = "   ")
    @DeleteMapping("/blank-description")
    public String blankDescription() {
        return "";
    }

    @Operation(summary = "Update the thing")
    @ApiResponse(responseCode = "409", description = "Something still references the thing")
    @PutMapping("/error-only")
    public String errorOnly() {
        return "";
    }
}
