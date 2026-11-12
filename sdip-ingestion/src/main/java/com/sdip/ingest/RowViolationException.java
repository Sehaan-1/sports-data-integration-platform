package com.sdip.ingest;

/**
 * A per-row validation failure. Treated as skippable by the job: the row is
 * quarantined into {@code ingestion_row_error} without poisoning its chunk.
 * Carries the offending row (number + raw content) for the skip listener.
 */
public class RowViolationException extends RuntimeException {

    private final Long rowNumber;
    private final String rawContent;

    public RowViolationException(String message) {
        this(message, null, null, null);
    }

    public RowViolationException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public RowViolationException(String message, Long rowNumber, String rawContent) {
        this(message, null, rowNumber, rawContent);
    }

    public RowViolationException(String message, Throwable cause, Long rowNumber, String rawContent) {
        super(message, cause);
        this.rowNumber = rowNumber;
        this.rawContent = rawContent;
    }

    public RowViolationException withRow(RowEnvelope row) {
        return new RowViolationException(getMessage(), getCause(), row.rowNumber(), row.raw());
    }

    public Long rowNumber() {
        return rowNumber;
    }

    public String rawContent() {
        return rawContent;
    }
}
