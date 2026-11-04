package edu.harvard.hms.dbmi.avillach.conventions.advicefixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/** A controller-local catch-all, which is outside the rule's scope because it is not an advice. */
@RestController
public class ControllerLocalHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unknown(Exception e) {
        return ResponseEntity.internalServerError().build();
    }
}
