package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.rest;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.HiddenResult;

/** A hidden controller, whose models still follow the convention. */
@Hidden
@RestController
public class HiddenModelsController {

    @GetMapping("/internal/result")
    public HiddenResult result() {
        return null;
    }
}
