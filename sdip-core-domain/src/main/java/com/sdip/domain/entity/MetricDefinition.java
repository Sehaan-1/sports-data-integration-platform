package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Canonical metric dictionary entry powering schema normalization (§4.4). */
@Entity
@Table(name = "metric_definition")
public class MetricDefinition {

    @Id
    @Column(length = 80)
    private String code;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(nullable = false, length = 30)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EntityType category;

    @Column(name = "min_valid")
    private Double minValid;

    @Column(name = "max_valid")
    private Double maxValid;

    protected MetricDefinition() {
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUnit() {
        return unit;
    }

    public EntityType getCategory() {
        return category;
    }

    public Double getMinValid() {
        return minValid;
    }

    public Double getMaxValid() {
        return maxValid;
    }

    public boolean inRange(double value) {
        if (minValid != null && value < minValid) {
            return false;
        }
        return maxValid == null || value <= maxValid;
    }
}
