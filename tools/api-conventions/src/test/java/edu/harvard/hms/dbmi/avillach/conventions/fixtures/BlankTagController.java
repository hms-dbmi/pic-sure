package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tag with a name but a blank description, so it violates R2. */
@Tag(name = "Blank", description = "   ")
@RestController
public class BlankTagController {

    @Operation(summary = "Read the thing")
    @ApiResponse(responseCode = "200", description = "The thing")
    @GetMapping("/blank")
    public String read() {
        return "";
    }
}
