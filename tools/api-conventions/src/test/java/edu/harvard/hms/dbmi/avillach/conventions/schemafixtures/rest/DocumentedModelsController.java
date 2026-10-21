package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.rest;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.Envelope;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.Shelf;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.Study;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.Unreached;

/** Handlers whose models satisfy the rule, and a parameter that is not a request body. */
@RestController
public class DocumentedModelsController {

    @GetMapping("/studies")
    public ResponseEntity<Envelope<List<Study>>> studies(@ModelAttribute Unreached filter) {
        return null;
    }

    @PostMapping("/studies")
    public Study create(@RequestBody Study study) {
        return null;
    }

    @GetMapping("/shelf")
    public Shelf shelf() {
        return null;
    }

    @GetMapping("/names")
    public List<String> names() {
        return null;
    }
}
