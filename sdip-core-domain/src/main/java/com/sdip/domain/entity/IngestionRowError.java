package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-row error capture (§4.2). Written in a SEPARATE transaction so chunk
 * rollback never wipes the evidence of a skipped row.
 */
@Entity
@Table(name = "ingestion_row_error")
public class IngestionRowError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "raw_content", columnDefinition = "TEXT")
    private String rawContent;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String violation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IngestionRowError() {
    }

    public IngestionRowError(UUID batchId, int rowNumber, String rawContent, String violation) {
        this.batchId = batchId;
        this.rowNumber = rowNumber;
        this.rawContent = truncate(rawContent, 4000);
        this.violation = truncate(violation, 4000);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    public Long getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getViolation() {
        return violation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
