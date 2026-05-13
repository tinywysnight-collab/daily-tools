package com.migration.s3.inventory;

public class ManifestUnavailableException extends RuntimeException {
    public ManifestUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
