package edu.harvard.hms.dbmi.avillach.hpds.exception;

import java.util.List;
import java.util.Map;

public class ValidationException extends Exception {
	
	private static final long serialVersionUID = -2558058901323272955L;
	
	private Map<String, List<String>> result;
	
	public ValidationException(Map<String, List<String>> result) {
		this.setResult(result);
	}

	public Map<String, List<String>> getResult() {
		return result;
	}

	public void setResult(Map<String, List<String>> result) {
		this.result = result;
	}
}
