package edu.harvard.dbmi.avillach.exception;

import java.net.URI;

public class ResourceCommunicationException extends RuntimeException {

	private static final long serialVersionUID = -3039213913753996987L;

	public ResourceCommunicationException(URI targetResource, String pathName, Exception e) {
		super("An error has occurred attempting to process a request for " + targetResource + "/" + pathName, e);
	}
	
}
