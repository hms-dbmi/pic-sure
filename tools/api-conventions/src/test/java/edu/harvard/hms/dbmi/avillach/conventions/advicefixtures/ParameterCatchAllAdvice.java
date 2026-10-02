package edu.harvard.hms.dbmi.avillach.conventions.advicefixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** A catch-all advice whose handler names no type, so Spring reads it from the parameter. */
@ControllerAdvice
public class ParameterCatchAllAdvice {

    @ExceptionHandler
    public ResponseEntity<String> unknown(Exception e) {
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler
    public ResponseEntity<String> narrow(IllegalArgumentException e) {
        return ResponseEntity.badRequest().build();
    }
}
