package edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.entity.WidgetEntity;

/** One handler per shape the rule rejects, as a return type first and then as a request body. */
@RestController
@SuppressWarnings("rawtypes")
public class UntypedSignaturesController {

    @GetMapping("/object")
    public Object object() {
        return null;
    }

    @GetMapping("/wildcard-entity")
    public ResponseEntity<?> wildcardEntity() {
        return null;
    }

    @GetMapping("/bounded-wildcard")
    public List<? extends Widget> boundedWildcard() {
        return null;
    }

    @GetMapping("/type-variable")
    public <T> T typeVariable() {
        return null;
    }

    @GetMapping("/raw-entity")
    public ResponseEntity rawEntity() {
        return null;
    }

    @GetMapping("/raw-list")
    public List rawList() {
        return null;
    }

    @GetMapping("/map")
    public Map<String, Object> map() {
        return null;
    }

    @GetMapping("/hash-map")
    public ResponseEntity<HashMap<String, String>> hashMap() {
        return null;
    }

    @GetMapping("/nested-map")
    public ResponseEntity<List<Map<String, Integer>>> nestedMap() {
        return null;
    }

    @GetMapping("/json-node")
    public JsonNode jsonNode() {
        return null;
    }

    @GetMapping("/object-node")
    public Mono<ObjectNode> objectNode() {
        return null;
    }

    @GetMapping("/page")
    public Page<Widget> page() {
        return null;
    }

    @GetMapping("/slice")
    public Slice<Widget> slice() {
        return null;
    }

    @GetMapping("/entity")
    public WidgetEntity entity() {
        return null;
    }

    @GetMapping("/entity-list")
    public List<WidgetEntity> entityList() {
        return null;
    }

    @GetMapping("/entity-array")
    public WidgetEntity[] entityArray() {
        return null;
    }

    @GetMapping("/wildcard-envelope")
    public Envelope<?> wildcardEnvelope() {
        return null;
    }

    @GetMapping("/two-faults")
    public Envelope<Map<String, List<?>>> mapInEnvelope() {
        return null;
    }

    @PostMapping("/map-body")
    public Widget mapBody(@RequestBody Map<String, Object> body) {
        return null;
    }

    @PostMapping("/json-body")
    public Widget jsonBody(String query, @RequestBody JsonNode body) {
        return null;
    }

    @PostMapping("/object-body")
    public Widget objectBody(@RequestBody Object body) {
        return null;
    }

    @PostMapping("/entity-body")
    public Widget entityBody(@RequestBody List<WidgetEntity> body) {
        return null;
    }

    @PostMapping("/both")
    public Map<String, String> both(@RequestBody Map<String, String> body) {
        return null;
    }
}
