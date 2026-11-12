package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/** Per-session GPS/micro-tech aggregates for one athlete (§3.3: aggregates, not raw ticks). */
@Entity
@Table(name = "gps_metric")
@Filter(name = SquadScoping.FILTER_NAME, condition = SquadScoping.CONDITION)
public class GpsMetric extends SourcedFact {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    @Column(name = "squad_id", nullable = false)
    private UUID squadId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "total_distance_m")
    private Double totalDistanceM;

    @Column(name = "hsr_distance_m")
    private Double hsrDistanceM;

    @Column(name = "player_load")
    private Double playerLoad;

    @Column(name = "max_velocity_kmh")
    private Double maxVelocityKmh;

    @Column(name = "session_minutes")
    private Double sessionMinutes;

    public UUID getId() {
        return id;
    }

    public UUID getAthleteId() {
        return athleteId;
    }

    public void setAthleteId(UUID athleteId) {
        this.athleteId = athleteId;
    }

    public UUID getSquadId() {
        return squadId;
    }

    public void setSquadId(UUID squadId) {
        this.squadId = squadId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Double getTotalDistanceM() {
        return totalDistanceM;
    }

    public void setTotalDistanceM(Double totalDistanceM) {
        this.totalDistanceM = totalDistanceM;
    }

    public Double getHsrDistanceM() {
        return hsrDistanceM;
    }

    public void setHsrDistanceM(Double hsrDistanceM) {
        this.hsrDistanceM = hsrDistanceM;
    }

    public Double getPlayerLoad() {
        return playerLoad;
    }

    public void setPlayerLoad(Double playerLoad) {
        this.playerLoad = playerLoad;
    }

    public Double getMaxVelocityKmh() {
        return maxVelocityKmh;
    }

    public void setMaxVelocityKmh(Double maxVelocityKmh) {
        this.maxVelocityKmh = maxVelocityKmh;
    }

    public Double getSessionMinutes() {
        return sessionMinutes;
    }

    public void setSessionMinutes(Double sessionMinutes) {
        this.sessionMinutes = sessionMinutes;
    }
}
