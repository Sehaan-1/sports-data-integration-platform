package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Reversible link binding a vendor identity to a master athlete (§4.3).
 * Links are never deleted, only revoked, so merges are never destructive.
 */
@Entity
@Table(name = "athlete_source_link")
public class AthleteSourceLink {

    public enum MatchTier {DETERMINISTIC, HEURISTIC, HUMAN_CONFIRMED}

    public enum LinkStatus {ACTIVE, REVOKED}

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "athlete_id", nullable = false)
    private Athlete athlete;

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    /**
     * Vendor identifier. When a link is revoked, a {@code "::revoked-<id>"}
     * suffix is appended to free the natural key for a correct future link,
     * while keeping this audit row.
     */
    @Column(name = "source_athlete_id", nullable = false, length = 200)
    private String sourceAthleteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_tier", nullable = false, length = 20)
    private MatchTier matchTier;

    @Column(name = "match_score")
    private Double matchScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private LinkStatus status = LinkStatus.ACTIVE;

    @Column(name = "linked_by")
    private String linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt = Instant.now();

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    protected AthleteSourceLink() {
    }

    public AthleteSourceLink(Athlete athlete, String sourceSystem, String sourceAthleteId,
                             MatchTier matchTier, Double matchScore, String linkedBy) {
        this.athlete = athlete;
        this.sourceSystem = sourceSystem;
        this.sourceAthleteId = sourceAthleteId;
        this.matchTier = matchTier;
        this.matchScore = matchScore;
        this.linkedBy = linkedBy;
    }

    public void revoke() {
        this.status = LinkStatus.REVOKED;
        this.unlinkedAt = Instant.now();
        this.sourceAthleteId = this.sourceAthleteId + "::revoked-" + this.id;
    }

    public UUID getId() {
        return id;
    }

    public Athlete getAthlete() {
        return athlete;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getSourceAthleteId() {
        return sourceAthleteId;
    }

    public MatchTier getMatchTier() {
        return matchTier;
    }

    public Double getMatchScore() {
        return matchScore;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public String getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getUnlinkedAt() {
        return unlinkedAt;
    }
}
