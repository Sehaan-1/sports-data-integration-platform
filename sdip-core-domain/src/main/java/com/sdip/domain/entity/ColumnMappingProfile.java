package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted, user-editable column-mapping profile (§4.2). The {@code mappings}
 * column holds a JSON document:
 * <pre>{ "mappings": [ {"header": "Max Vel (km/h)", "field": "GPS_MAX_VELOCITY_KMH", "unit": "km/h"} ] }</pre>
 * Special pseudo-fields: ATHLETE_NAME, ATHLETE_EMAIL, ATHLETE_DOB,
 * ATHLETE_EXTERNAL_ID, RECORDED_AT, TEST_TYPE, SURVEY_DATE.
 */
@Entity
@Table(name = "column_mapping_profile")
public class ColumnMappingProfile {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String mappings;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ColumnMappingProfile() {
    }

    public ColumnMappingProfile(String name, EntityType entityType, String sourceSystem, String mappings) {
        this.name = name;
        this.entityType = entityType;
        this.sourceSystem = sourceSystem;
        this.mappings = mappings;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(EntityType entityType) {
        this.entityType = entityType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getMappings() {
        return mappings;
    }

    public void setMappings(String mappings) {
        this.mappings = mappings;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
