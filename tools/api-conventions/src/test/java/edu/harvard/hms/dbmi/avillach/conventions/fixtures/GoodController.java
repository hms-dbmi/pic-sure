package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Satisfies every rule. A single ApiResponse and an ApiResponses container both appear on purpose. */
@Tag(name = "Good", description = "A controller that satisfies every rule")
@RestController
@RequestMapping("/good")
public class GoodController {

    @Operation(summary = "Read the thing")
    @ApiResponse(responseCode = "200", description = "The thing")
    @GetMapping
    public String read() {
        return "";
    }

    @Operation(summary = "Create the thing")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The created thing"),
        @ApiResponse(responseCode = "400", description = "The request was malformed")
    })
    @PostMapping
    public String create() {
        return "";
    }

    @Hidden
    @GetMapping("/hidden-method")
    public String hiddenMethod() {
        return "";
    }

    /** Not a handler: it carries no mapping annotation, so no rule applies to it. */
    public String helper() {
        return "";
    }
}
