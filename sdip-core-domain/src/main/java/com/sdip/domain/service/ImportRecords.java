package com.sdip.domain.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Canonical, source-agnostic import records produced by files and connectors
 * alike. {@code syncedAt} records the vendor sync moment (lineage column
 * {@code last_vendor_sync}); {@code batchId} ties the row to the ingestion log.
 */
public final class ImportRecords {

    private ImportRecords() {
    }

    public record GpsImport(UUID athleteId, UUID squadId, Instant recordedAt,
                            Double totalDistanceM, Double hsrDistanceM, Double playerLoad,
                            Double maxVelocityKmh, Double sessionMinutes,
                            String sourceSystem, String sourceRecordId,
                            UUID batchId, Instant syncedAt) {
    }

    public record ForceImport(UUID athleteId, UUID squadId, String testType, Instant testedAt,
                              Double peakForceN, Double asymmetryPct, Double rsiModified, Double peakPowerW,
                              String sourceSystem, String sourceRecordId,
                              UUID batchId, Instant syncedAt) {
    }

    public record WellnessImport(UUID athleteId, UUID squadId, LocalDate surveyDate,
                                 Double sleepScore, Double fatigue, Double soreness,
                                 Double stress, Double mood,
                                 String sourceSystem, String sourceRecordId,
                                 UUID batchId, Instant syncedAt) {
    }
}
