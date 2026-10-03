package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.rest;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.library.SharedBroken;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.BlankClassDescription;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.ForeignTypeHolder;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.LegacyDto;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.MissingCollectionExamples;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.MissingFieldDescriptions;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.MissingScalarExamples;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.NoClassDescription;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.Shape;
import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model.UndocumentedRequest;

/** One handler per way a model can miss the convention. */
@RestController
public class UndocumentedModelsController {

    @GetMapping("/no-class-description")
    public NoClassDescription noClassDescription() {
        return null;
    }

    @GetMapping("/blank-class-description")
    public ResponseEntity<List<BlankClassDescription>> blankClassDescription() {
        return null;
    }

    @GetMapping("/missing-field-descriptions")
    public MissingFieldDescriptions missingFieldDescriptions() {
        return null;
    }

    @GetMapping("/missing-scalar-examples")
    public MissingScalarExamples[] missingScalarExamples() {
        return null;
    }

    @GetMapping("/missing-collection-examples")
    public Map<String, MissingCollectionExamples> missingCollectionExamples() {
        return null;
    }

    @GetMapping("/legacy")
    public LegacyDto legacy() {
        return null;
    }

    @GetMapping("/shape")
    public Shape shape() {
        return null;
    }

    @GetMapping("/foreign")
    public ForeignTypeHolder foreign() {
        return null;
    }

    @GetMapping("/shared")
    public SharedBroken shared() {
        return null;
    }

    @PostMapping("/request")
    public void request(@RequestBody UndocumentedRequest request) {}
}
