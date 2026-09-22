package edu.harvard.hms.dbmi.avillach.conventions.fixtures;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Carries both Tag and Hidden, which R1 rejects because Hidden wins at runtime and the Tag is dead metadata. */
@Tag(name = "Both", description = "A controller that contradicts itself")
@Hidden
@RestController
public class BothTagAndHiddenController {

    @GetMapping("/both")
    public String read() {
        return "";
    }
}
