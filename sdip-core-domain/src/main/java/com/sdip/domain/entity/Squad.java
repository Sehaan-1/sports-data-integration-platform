package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "squad")
public class Squad {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "tenant_code", nullable = false, length = 60)
    private String tenantCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Squad() {
    }

    public Squad(String name, String tenantCode) {
        this.name = name;
        this.tenantCode = tenantCode;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
