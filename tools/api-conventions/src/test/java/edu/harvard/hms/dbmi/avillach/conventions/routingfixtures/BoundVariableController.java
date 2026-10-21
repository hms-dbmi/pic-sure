package edu.harvard.hms.dbmi.avillach.conventions.routingfixtures;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Every shape of named path variable the rule accepts, and an unnamed one it skips. */
@RestController
@RequestMapping({"/dataset/{datasetId}", "/legacy/dataset/{datasetId}"})
public class BoundVariableController {

    @GetMapping("/concept/{conceptId}")
    public String fromClassAndMethod(@PathVariable("datasetId") String datasetId, @PathVariable("conceptId") String conceptId) {
        return datasetId + conceptId;
    }

    @GetMapping("/version/{version:[0-9]{1,3}}")
    public String withRegex(@PathVariable(name = "version") String version) {
        return version;
    }

    @PostMapping(path = {"/export", "/export/{format}"})
    public String inOneOfSeveralPaths(@PathVariable(value = "format", required = false) String format) {
        return format;
    }

    @RequestMapping(value = "/files/{*rest}", method = RequestMethod.GET)
    public String capture(@PathVariable("rest") String rest) {
        return rest;
    }

    @DeleteMapping("/entry")
    public String unnamed(@PathVariable String entryId) {
        return entryId;
    }
}
