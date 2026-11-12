package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/** Neuromuscular test result (ForceDecks / NordBord / DynaMo aggregates). */
@Entity
@Table(name = "force_test")
@Filter(name = SquadScoping.FILTER_NAME, condition = SquadScoping.CONDITION)
public class ForceTest extends SourcedFact {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    @Column(name = "squad_id", nullable = false)
    private UUID squadId;

    @Column(name = "test_type", nullable = false, length = 60)
    private String testType;

    @Column(name = "tested_at", nullable = false)
    private Instant testedAt;

    @Column(name = "peak_force_n")
    private Double peakForceN;

    @Column(name = "asymmetry_pct")
    private Double asymmetryPct;

    @Column(name = "rsi_modified")
    private Double rsiModified;

    @Column(name = "peak_power_w")
    private Double peakPowerW;

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

    public String getTestType() {
        return testType;
    }

    public void setTestType(String testType) {
        this.testType = testType;
    }

    public Instant getTestedAt() {
        return testedAt;
    }

    public void setTestedAt(Instant testedAt) {
        this.testedAt = testedAt;
    }

    public Double getPeakForceN() {
        return peakForceN;
    }

    public void setPeakForceN(Double peakForceN) {
        this.peakForceN = peakForceN;
    }

    public Double getAsymmetryPct() {
        return asymmetryPct;
    }

    public void setAsymmetryPct(Double asymmetryPct) {
        this.asymmetryPct = asymmetryPct;
    }

    public Double getRsiModified() {
        return rsiModified;
    }

    public void setRsiModified(Double rsiModified) {
        this.rsiModified = rsiModified;
    }

    public Double getPeakPowerW() {
        return peakPowerW;
    }

    public void setPeakPowerW(Double peakPowerW) {
        this.peakPowerW = peakPowerW;
    }
}
