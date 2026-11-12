package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "training_session")
@Filter(name = SquadScoping.FILTER_NAME, condition = SquadScoping.CONDITION)
public class TrainingSession {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "squad_id", nullable = false)
    private Squad squad;

    @Column(name = "external_ref", length = 120)
    private String externalRef;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "session_type", length = 60)
    private String sessionType;

    @Column(length = 200)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TrainingSession() {
    }

    public TrainingSession(Squad squad, LocalDate sessionDate, String sessionType, String name) {
        this.squad = squad;
        this.sessionDate = sessionDate;
        this.sessionType = sessionType;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public Squad getSquad() {
        return squad;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public String getSessionType() {
        return sessionType;
    }

    public String getName() {
        return name;
    }
}
