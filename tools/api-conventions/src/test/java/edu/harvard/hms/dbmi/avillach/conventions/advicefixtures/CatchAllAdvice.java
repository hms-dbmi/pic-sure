package edu.harvard.hms.dbmi.avillach.conventions.advicefixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A catch-all advice that does not extend the base class, naming its types in the annotation. */
@RestControllerAdvice
public class CatchAllAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unknown(Exception e) {
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler({IllegalStateException.class, RuntimeException.class})
    public ResponseEntity<String> runtime(RuntimeException e) {
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler(exception = Throwable.class)
    public ResponseEntity<String> throwable(Throwable e) {
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> narrow(Exception e) {
        return ResponseEntity.badRequest().build();
    }
}
