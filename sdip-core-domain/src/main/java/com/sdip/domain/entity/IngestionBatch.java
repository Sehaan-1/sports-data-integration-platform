package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable ingestion lineage log — one row per sync job or file upload (§4.4). */
@Entity
@Table(name = "ingestion_batch")
public class IngestionBatch {

    public enum BatchType {FILE_CSV, FILE_XLSX, CONNECTOR}

    public enum BatchStatus {STARTED, COMPLETED, COMPLETED_WITH_SKIPS, FAILED}

    @Id
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_type", nullable = false, length = 20)
    private BatchType batchType;

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    @Column(name = "file_name", length = 300)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private BatchStatus status = BatchStatus.STARTED;

    @Column(name = "rows_read", nullable = false)
    private long rowsRead = 0;

    @Column(name = "rows_written", nullable = false)
    private long rowsWritten = 0;

    @Column(name = "rows_skipped", nullable = false)
    private long rowsSkipped = 0;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    protected IngestionBatch() {
    }

    public IngestionBatch(BatchType batchType, String sourceSystem, String fileName, String triggeredBy) {
        this.batchType = batchType;
        this.sourceSystem = sourceSystem;
        this.fileName = fileName;
        this.triggeredBy = triggeredBy;
    }

    public void finish(BatchStatus status, long rowsRead, long rowsWritten, long rowsSkipped, String errorSummary) {
        this.status = status;
        this.rowsRead = rowsRead;
        this.rowsWritten = rowsWritten;
        this.rowsSkipped = rowsSkipped;
        this.finishedAt = Instant.now();
        this.durationMs = this.finishedAt.toEpochMilli() - this.startedAt.toEpochMilli();
        this.errorSummary = errorSummary;
    }

    public UUID getId() {
        return id;
    }

    public BatchType getBatchType() {
        return batchType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getFileName() {
        return fileName;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public long getRowsRead() {
        return rowsRead;
    }

    public long getRowsWritten() {
        return rowsWritten;
    }

    public long getRowsSkipped() {
        return rowsSkipped;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
