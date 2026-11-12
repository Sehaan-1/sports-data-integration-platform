package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** Vendor API credentials encrypted at rest with AES-GCM-256 (§4.5). */
@Entity
@Table(name = "vendor_credential")
public class VendorCredential {

    @Id
    @Column(length = 40)
    private String vendor;

    @Lob
    @Column(name = "payload_enc", nullable = false)
    private byte[] payloadEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

    protected VendorCredential() {
    }

    public VendorCredential(String vendor, byte[] payloadEnc, String updatedBy) {
        this.vendor = vendor;
        this.payloadEnc = payloadEnc;
        this.updatedBy = updatedBy;
    }

    public String getVendor() {
        return vendor;
    }

    public byte[] getPayloadEnc() {
        return payloadEnc;
    }

    public void setPayloadEnc(byte[] payloadEnc) {
        this.payloadEnc = payloadEnc;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
