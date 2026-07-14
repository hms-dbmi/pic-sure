package edu.harvard.dbmi.avillach.visualization.error;

public class BadVisualizationRequestException extends VisualizationException {

    public BadVisualizationRequestException(String message) {
        super(message);
    }

    public BadVisualizationRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
