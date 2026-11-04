package edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.rest;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A hidden controller is absent from the document and still has to be typed. */
@Hidden
@RestController
public class HiddenUntypedController {

    @PostMapping("/internal/save")
    public Map<String, UUID> save() {
        return null;
    }
}
