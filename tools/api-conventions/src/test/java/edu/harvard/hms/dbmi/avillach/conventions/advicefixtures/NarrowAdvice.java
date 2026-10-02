package edu.harvard.hms.dbmi.avillach.conventions.advicefixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** An advice that handles only its own exception type, which leaves the framework's mapping alone. */
@RestControllerAdvice
public class NarrowAdvice {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handle(IllegalStateException e) {
        return ResponseEntity.status(409).build();
    }
}
