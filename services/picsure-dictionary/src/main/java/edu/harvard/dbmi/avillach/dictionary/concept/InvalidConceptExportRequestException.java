package edu.harvard.dbmi.avillach.dictionary.concept;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signals an export metadata request the dictionary will not run: a list is missing, there are too many concept paths, or a metadata key is
 * not allowed for export. Spring answers it with 400.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidConceptExportRequestException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what makes the request invalid
     */
    public InvalidConceptExportRequestException(String message) {
        super(message);
    }
}
