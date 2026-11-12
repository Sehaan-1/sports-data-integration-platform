package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Master Athlete Index entry. The {@code squadFilter} Hibernate filter
 * (defined here, referenced by all squad-scoped entities) enforces
 * row-level tenant scoping; it is enabled per request by the security
 * module for non-ADMIN principals.
 */
@Entity
@Table(name = "athlete")
@FilterDef(name = SquadScoping.FILTER_NAME, parameters = @ParamDef(name = SquadScoping.FILTER_PARAM, type = UUID.class))
@Filter(name = SquadScoping.FILTER_NAME, condition = SquadScoping.CONDITION)
public class Athlete {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "squad_id", nullable = false)
    private Squad squad;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    private LocalDate dob;

    @Column(unique = true)
    private String email;

    @Column(length = 20)
    private String gender;

    /** True when auto-created below the quarantine threshold (§4.3 Tier 2). */
    @Column(nullable = false)
    private boolean provisional = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Athlete() {
    }

    public Athlete(Squad squad, String fullName, LocalDate dob, String email, boolean provisional) {
        this.squad = squad;
        this.fullName = fullName;
        this.dob = dob;
        this.email = email;
        this.provisional = provisional;
    }

    public UUID getId() {
        return id;
    }

    public Squad getSquad() {
        return squad;
    }

    public void setSquad(Squad squad) {
        this.squad = squad;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public LocalDate getDob() {
        return dob;
    }

    public void setDob(LocalDate dob) {
        this.dob = dob;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public void setProvisional(boolean provisional) {
        this.provisional = provisional;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
