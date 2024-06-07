package edu.harvard.hms.dbmi.avillach.auth.model.response;

public record PicSureResponseBody<T>(String message, T content) {
}
