package edu.harvard.hms.dbmi.avillach.auth.model.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.HashMap;
import java.util.Map;

public class PICSUREResponse {

    private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.APPLICATION_JSON;
    private static final HttpStatus DEFAULT_RESPONSE_ERROR_CODE = HttpStatus.INTERNAL_SERVER_ERROR;

    public static ResponseEntity<?> success() {
        return new ResponseEntity<>(HttpStatus.OK);
    }

    public static <T> ResponseEntity<T> success(T content) {
        return ResponseEntity.ok(content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> success(String message, T content) {
        PicSureResponseBody<T> body = new PicSureResponseBody<>(message, content);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    public static <T> ResponseEntity<T> error(T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> error(String message, T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, message, content);
    }

    public static <T> ResponseEntity<T> error(HttpStatus status, T content) {
        if (status == null) {
            status = DEFAULT_RESPONSE_ERROR_CODE;
        }
        return new ResponseEntity<>(content, status);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> error(HttpStatus status, String message, T content) {
        return new ResponseEntity<>(new PicSureResponseBody<>(message, content), status);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> applicationError(T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, "Application error", content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> applicationError(String message, T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, message, content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> riError(T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, "RI error", content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> riError(String message, T content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, message, content);
    }

    public static <T> ResponseEntity<T> protocolError(T content) {
        return error(HttpStatus.BAD_REQUEST, content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> protocolError(String message, T content) {
        return error(HttpStatus.BAD_REQUEST, message, content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> unauthorizedError(T content) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", content);
    }

    public static <T> ResponseEntity<PicSureResponseBody<T>> unauthorizedError(String message, T content) {
        return error(HttpStatus.UNAUTHORIZED, message, content);
    }
}

