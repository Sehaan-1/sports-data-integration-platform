package com.sdip.connectors;

/** Vendor API call failure. {@code retryable} marks 429/5xx/transport faults. */
public class VendorApiException extends RuntimeException {

    private final boolean retryable;
    private final int status;

    public VendorApiException(String message, boolean retryable, int status) {
        super(message);
        this.retryable = retryable;
        this.status = status;
    }

    public VendorApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.status = -1;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int status() {
        return status;
    }
}
