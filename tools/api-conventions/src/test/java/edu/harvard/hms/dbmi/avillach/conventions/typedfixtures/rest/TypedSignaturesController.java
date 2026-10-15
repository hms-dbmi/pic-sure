package edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.rest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** One handler per shape the rule accepts, next to a map that is not a request body and a method that is not a handler. */
@RestController
public class TypedSignaturesController {

    @GetMapping("/string")
    public String string() {
        return null;
    }

    @GetMapping("/bytes")
    public byte[] bytes() {
        return null;
    }

    @GetMapping("/stream")
    public ResponseEntity<InputStreamResource> stream() {
        return null;
    }

    @DeleteMapping("/boxed-void")
    public ResponseEntity<Void> boxedVoid() {
        return null;
    }

    @DeleteMapping("/nothing")
    public void nothing() {}

    @GetMapping("/flag")
    public Boolean flag() {
        return null;
    }

    @GetMapping("/model")
    public Widget model() {
        return null;
    }

    @GetMapping("/list")
    public List<Widget> list() {
        return null;
    }

    @GetMapping("/set")
    public Set<String> set() {
        return null;
    }

    @GetMapping("/array")
    public Widget[] array() {
        return null;
    }

    @GetMapping("/entity-of-list")
    public ResponseEntity<List<Widget>> entityOfList() {
        return null;
    }

    @GetMapping("/mono")
    public Mono<Widget> mono() {
        return null;
    }

    @GetMapping("/optional")
    public Optional<Widget> optional() {
        return null;
    }

    @GetMapping("/envelope")
    public Envelope<List<Widget>> envelope() {
        return null;
    }

    @PostMapping("/model-body")
    public Widget modelBody(@RequestBody Widget body) {
        return null;
    }

    @PostMapping("/list-body")
    public Widget listBody(@RequestBody List<Widget> body) {
        return null;
    }

    @PostMapping("/text-body")
    public String textBody(@RequestBody String body) {
        return null;
    }

    @GetMapping("/query-parameters")
    public Widget queryParameters(@RequestParam Map<String, String> parameters) {
        return null;
    }

    @Hidden
    @GetMapping("/hidden-typed")
    public Widget hiddenTyped() {
        return null;
    }

    public Map<String, Object> helper() {
        return null;
    }
}
