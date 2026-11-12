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
 * Human-in-the-loop quarantine queue entry (§4.3). Holds an ambiguous
 * candidate link until an administrator confirms, redirects, or rejects it.
 */
@Entity
@Table(name = "link_quarantine")
public class LinkQuarantine {

    public enum QuarantineStatus {PENDING, CONFIRMED, REJECTED}

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    @Column(name = "source_athlete_id", nullable = false, length = 120)
    private String sourceAthleteId;

    /** Best heuristic candidate, if any (may be null when score sat below all candidates). */
    @Column(name = "candidate_athlete_id")
    private UUID candidateAthleteId;

    @Column(nullable = false)
    private double score;

    /** JSON snapshot of the observed identity payload, for reviewer context. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 14)
    private QuarantineStatus status = QuarantineStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    protected LinkQuarantine() {
    }

    public LinkQuarantine(String sourceSystem, String sourceAthleteId, UUID candidateAthleteId,
                          double score, String payload) {
        this.sourceSystem = sourceSystem;
        this.sourceAthleteId = sourceAthleteId;
        this.candidateAthleteId = candidateAthleteId;
        this.score = score;
        this.payload = payload;
    }

    public void resolve(QuarantineStatus status, String resolvedBy, String note) {
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = Instant.now();
        this.resolutionNote = note;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getSourceAthleteId() {
        return sourceAthleteId;
    }

    public UUID getCandidateAthleteId() {
        return candidateAthleteId;
    }

    public double getScore() {
        return score;
    }

    public String getPayload() {
        return payload;
    }

    public QuarantineStatus getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
