package edu.harvard.hms.dbmi.avillach.conventions.advicefixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A catch-all advice that reaches the base class through a local superclass. */
@RestControllerAdvice
public class IndirectBaseHandlerAdvice extends BaseHandlerAdvice {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> runtime(RuntimeException e) {
        return ResponseEntity.internalServerError().build();
    }
}
