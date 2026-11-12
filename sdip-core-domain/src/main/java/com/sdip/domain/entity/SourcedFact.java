package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared data-lineage columns (§4.4): every fact row is traceable to its
 * source system, vendor record, and ingestion batch. The pair
 * (source_system, source_record_id) is the natural key backing idempotent
 * upserts (§4.1 / C4).
 */
@MappedSuperclass
public abstract class SourcedFact {

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    @Column(name = "source_record_id", nullable = false, length = 300)
    private String sourceRecordId;

    @Column(name = "ingestion_batch_id")
    private UUID ingestionBatchId;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt = Instant.now();

    @Column(name = "last_vendor_sync")
    private Instant lastVendorSync;

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(String sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public UUID getIngestionBatchId() {
        return ingestionBatchId;
    }

    public void setIngestionBatchId(UUID ingestionBatchId) {
        this.ingestionBatchId = ingestionBatchId;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public Instant getLastVendorSync() {
        return lastVendorSync;
    }

    public void setLastVendorSync(Instant lastVendorSync) {
        this.lastVendorSync = lastVendorSync;
    }
}
