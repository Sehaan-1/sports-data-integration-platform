package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Per-connector sync watermark for watermark-based incremental sync (§4.1). */
@Entity
@Table(name = "connector_state")
public class ConnectorState {

    @Id
    @Column(length = 40)
    private String vendor;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConnectorState() {
    }

    public ConnectorState(String vendor) {
        this.vendor = vendor;
    }

    public String getVendor() {
        return vendor;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void advanceTo(Instant syncedAt) {
        this.lastSyncedAt = syncedAt;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
