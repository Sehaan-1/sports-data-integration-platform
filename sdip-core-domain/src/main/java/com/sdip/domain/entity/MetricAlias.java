package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps a normalized vendor header to a canonical metric code. */
@Entity
@Table(name = "metric_alias")
public class MetricAlias {

    /** Normalized form: lower-cased, whitespace-collapsed. */
    @Id
    @Column(length = 200)
    private String alias;

    @Column(name = "metric_code", nullable = false, length = 80)
    private String metricCode;

    protected MetricAlias() {
    }

    public MetricAlias(String alias, String metricCode) {
        this.alias = alias;
        this.metricCode = metricCode;
    }

    public String getAlias() {
        return alias;
    }

    public String getMetricCode() {
        return metricCode;
    }
}
