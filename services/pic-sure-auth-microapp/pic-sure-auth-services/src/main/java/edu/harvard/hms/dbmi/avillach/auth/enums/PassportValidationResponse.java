package edu.harvard.hms.dbmi.avillach.auth.enums;

public enum PassportValidationResponse {
    // The Visa’s signature is valid, the Visa has not expired, a valid txn claim is present
    VALID("Valid"),
    // The encoded Visa’s signature failed validation, is missing or incorrect
    INVALID("Invalid"),
    // The Passport payload or Visa parameter is missing
    MISSING("Missing"),
    // The format of the Passport or Visa is incorrect
    INVALID_PASSPORT("Invalid Passport"),
    // The Visa has expired (as indicated by the “exp” claim)
    VISA_EXPIRED("Visa Expired"),
    // The Transaction claim (txn) has an error or is missing
    TXN_ERROR("Txn Error"),
    // The Expiration claim (exp) has an error or is missing
    EXPIRATION_ERROR("Expiration Error"),
    // A DB related validation error occurred
    VALIDATION_ERROR("Validation Error"),
    // The Visa was expired because the required polling time was exceeded
    EXPIRED_POLLING("Expired Polling"),
    // There is a change in the dbGaP permissions associated with the user
    PERMISSION_UPDATE("Permission Update");

    private final String value;

    PassportValidationResponse(String value) {
        this.value = value;
    }

    // FromValue
    public static PassportValidationResponse fromValue(String value) {
        for (PassportValidationResponse response : PassportValidationResponse.values()) {
            if (response.getValue().equals(value)) {
                return response;
            }
        }
        return null;
    }

    public String getValue() {
        return value;
    }

}
