package com.sdip.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.util.UUID;

/** Daily wellness/self-report survey response. */
@Entity
@Table(name = "wellness_survey")
@Filter(name = SquadScoping.FILTER_NAME, condition = SquadScoping.CONDITION)
public class WellnessSurvey extends SourcedFact {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    @Column(name = "squad_id", nullable = false)
    private UUID squadId;

    @Column(name = "survey_date", nullable = false)
    private LocalDate surveyDate;

    @Column(name = "sleep_score")
    private Double sleepScore;

    @Column(name = "fatigue")
    private Double fatigue;

    @Column(name = "soreness")
    private Double soreness;

    @Column(name = "stress")
    private Double stress;

    @Column(name = "mood")
    private Double mood;

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

    public LocalDate getSurveyDate() {
        return surveyDate;
    }

    public void setSurveyDate(LocalDate surveyDate) {
        this.surveyDate = surveyDate;
    }

    public Double getSleepScore() {
        return sleepScore;
    }

    public void setSleepScore(Double sleepScore) {
        this.sleepScore = sleepScore;
    }

    public Double getFatigue() {
        return fatigue;
    }

    public void setFatigue(Double fatigue) {
        this.fatigue = fatigue;
    }

    public Double getSoreness() {
        return soreness;
    }

    public void setSoreness(Double soreness) {
        this.soreness = soreness;
    }

    public Double getStress() {
        return stress;
    }

    public void setStress(Double stress) {
        this.stress = stress;
    }

    public Double getMood() {
        return mood;
    }

    public void setMood(Double mood) {
        this.mood = mood;
    }
}
