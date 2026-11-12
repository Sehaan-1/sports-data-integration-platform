package com.sdip.ingest;

import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.ColumnMappingProfile;
import com.sdip.domain.entity.EntityType;
import com.sdip.domain.link.EntityLinker;
import com.sdip.domain.link.IdentityRecord;
import com.sdip.domain.link.LinkOutcome;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.service.ImportRecords.ForceImport;
import com.sdip.domain.service.ImportRecords.GpsImport;
import com.sdip.domain.service.ImportRecords.WellnessImport;
import com.sdip.domain.service.MetricDictionary;
import com.sdip.domain.service.RowErrorRecorder;
import com.sdip.domain.service.UnitConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ItemProcessor: row → canonical typed record.
 * <ul>
 *   <li>Builds the {@link MappingPlan} once from the persisted profile +
 *       metric dictionary aliases (§4.2 schema normalization).</li>
 *   <li>Resolves the athlete through the tiered {@link EntityLinker} (§4.3);
 *       ambiguous rows are routed to quarantine and filtered (never loaded).</li>
 *   <li>Performs unit conversion and physiological range validation against
 *       the dictionary (§6).</li>
 *   <li>Derives a deterministic natural key so re-ingesting the same file is
 *       a no-op (C4 idempotency).</li>
 * </ul>
 */
public class MappingRowProcessor implements ItemProcessor<RowEnvelope, ImportChunk> {

    private static final Logger log = LoggerFactory.getLogger(MappingRowProcessor.class);

    private final ColumnMappingService mappingService;
    private final MetricDictionary dictionary;
    private final EntityLinker linker;
    private final AthleteRepository athletes;
    private final RowErrorRecorder rowErrors;

    private final String profileName;
    private final UUID squadId;
    private final UUID batchId;

    private MappingPlan plan;

    /**
     * Per-step identity cache (RQ1 throughput): vendor identities are stable
     * within a run, so each externalId/email is resolved through the tiered
     * linker exactly once; subsequent rows hit memory. A tombstone marks
     * identities previously routed to quarantine.
     */
    private final Map<String, UUID> athleteResolutionCache = new java.util.HashMap<>();
    private final Map<UUID, UUID> squadOfAthleteCache = new java.util.HashMap<>();
    private static final UUID QUARANTINE_TOMBSTONE = new UUID(0L, 0L);

    /**
     * The cache must NOT survive a chunk rollback: a cached provisional
     * athlete/link was created inside the failed transaction and no longer
     * exists when Spring Batch rescans the chunk — re-using its id would
     * violate the FK at write time. (Found via CsvIngestionFlowTest.)
     */
    private void registerCacheInvalidationOnRollback() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        athleteResolutionCache.clear();
                        squadOfAthleteCache.clear();
                    }
                }
            });
        } catch (IllegalStateException ignored) {
        }
    }

    public MappingRowProcessor(ColumnMappingService mappingService,
                               MetricDictionary dictionary,
                               EntityLinker linker,
                               AthleteRepository athletes,
                               RowErrorRecorder rowErrors,
                               String profileName, UUID squadId, UUID batchId) {
        this.mappingService = mappingService;
        this.dictionary = dictionary;
        this.linker = linker;
        this.athletes = athletes;
        this.rowErrors = rowErrors;
        this.profileName = profileName;
        this.squadId = squadId;
        this.batchId = batchId;
    }

    @Override
    public ImportChunk process(RowEnvelope row) {
        try {
            return doProcess(row);
        } catch (RowViolationException e) {
            throw e.withRow(row);
        } catch (Exception e) {
            throw new RowViolationException("unexpected processing error: " + e.getMessage(), e,
                    row.rowNumber(), row.raw());
        }
    }

    private ImportChunk doProcess(RowEnvelope row) {
        if (plan == null) {
            ColumnMappingProfile profile = mappingService.requireProfile(profileName);
            plan = mappingService.buildPlan(profile, List.copyOf(row.cells().keySet()));
        }
        Map<String, String> c = row.cells();

        String name = value(c, MappingPlan.ATHLETE_NAME);
        String email = value(c, MappingPlan.ATHLETE_EMAIL);
        String dobRaw = value(c, MappingPlan.ATHLETE_DOB);
        String externalId = value(c, MappingPlan.ATHLETE_EXTERNAL_ID);
        if ((name == null || name.isBlank()) && externalId == null && (email == null || email.isBlank())) {
            throw new RowViolationException("no athlete identity column populated (need name, id, or email)");
        }
        LocalDate dob = null;
        if (dobRaw != null && !dobRaw.isBlank()) {
            try {
                dob = ParsingSupport.parseDate(dobRaw);
            } catch (RowViolationException e) {
                throw new RowViolationException("invalid DOB '" + dobRaw + "'");
            }
        }

        String trimmedEmail = email != null ? email.trim() : null;
        String cacheKey = externalId != null ? "E|" + externalId
                : (trimmedEmail != null ? "M|" + trimmedEmail.toLowerCase() : null);

        UUID athleteId;
        if (cacheKey != null && athleteResolutionCache.containsKey(cacheKey)) {
            UUID cached = athleteResolutionCache.get(cacheKey);
            if (QUARANTINE_TOMBSTONE.equals(cached)) {
                rowErrors.record(batchId, (int) row.rowNumber(), row.raw(),
                        "athlete previously routed to link quarantine in this batch");
                return null;
            }
            athleteId = cached;
        } else {
            LinkOutcome outcome = linker.resolve(new IdentityRecord(
                    plan.sourceSystem(), externalId, name, dob, trimmedEmail, squadId));

            if (!outcome.linkable()) {
                // Routed to the human quarantine queue: visible, never silent (§4.2)
                rowErrors.record(batchId, (int) row.rowNumber(), row.raw(),
                        "athlete routed to link quarantine (item " + outcome.quarantineId()
                                + ", score " + String.format("%.3f", outcome.score()) + ")");
                if (cacheKey != null) {
                    athleteResolutionCache.put(cacheKey, QUARANTINE_TOMBSTONE);
                }
                return null;
            }
            athleteId = outcome.athleteId();
            if (cacheKey != null) {
                athleteResolutionCache.put(cacheKey, athleteId);
                registerCacheInvalidationOnRollback();
            }
        }
        UUID athleteSquad = squadOfAthleteCache.computeIfAbsent(athleteId,
                aid -> athletes.findById(aid)
                        .map(Athlete::getSquad).map(s -> s.getId()).orElse(squadId));

        // Natural key composition (C4): vendor record ids are unique per ENTITY
        // (e.g. a Catapult session id), not per athlete-entity pair, so the
        // athlete's vendor id is composed in when both exist. Deterministic
        // either way → re-ingesting the same file is always a no-op.
        String rawRecordId = value(c, MappingPlan.RECORD_ID);
        String recordId = rawRecordId != null && externalId != null
                ? rawRecordId + ";" + externalId
                : rawRecordId;
        Instant syncedAt = Instant.now();

        return switch (plan.entityType()) {
            case GPS -> {
                String recordedRaw = required(c, MappingPlan.RECORDED_AT, "GPS rows need a RECORDED_AT/date column");
                Instant recordedAt = ParsingSupport.parseInstant(recordedRaw);
                GpsImport g = new GpsImport(athleteId, athleteSquad, recordedAt,
                        metricGps(c, "GPS_TOTAL_DISTANCE_M"),
                        metricGps(c, "GPS_HSR_DISTANCE_M"),
                        metricGps(c, "GPS_PLAYER_LOAD"),
                        metricGps(c, "GPS_MAX_VELOCITY_KMH"),
                        metricGps(c, "GPS_SESSION_MINUTES"),
                        plan.sourceSystem(),
                        recordId != null ? recordId : naturalKey("GPS", athleteId, recordedRaw, row.raw()),
                        batchId, syncedAt);
                yield ImportChunk.gpsOnly(g);
            }
            case FORCE -> {
                String testedRaw = required(c, MappingPlan.RECORDED_AT, "FORCE rows need a RECORDED_AT/date column");
                Instant testedAt = ParsingSupport.parseInstant(testedRaw);
                String testType = value(c, MappingPlan.TEST_TYPE);
                ForceImport f = new ForceImport(athleteId, athleteSquad,
                        testType != null ? testType : "UNKNOWN", testedAt,
                        metricGps(c, "FORCE_PEAK_FORCE_N"),
                        metricGps(c, "FORCE_ASYMMETRY_PCT"),
                        metricGps(c, "FORCE_RSI_MODIFIED"),
                        metricGps(c, "FORCE_PEAK_POWER_W"),
                        plan.sourceSystem(),
                        recordId != null ? recordId : naturalKey("FORCE", athleteId, testedRaw + testType, row.raw()),
                        batchId, syncedAt);
                yield ImportChunk.forceOnly(f);
            }
            case WELLNESS -> {
                String dateRaw = value(c, MappingPlan.SURVEY_DATE);
                if (dateRaw == null) {
                    dateRaw = required(c, MappingPlan.RECORDED_AT, "WELLNESS rows need a SURVEY_DATE/date column");
                }
                LocalDate date = ParsingSupport.parseDate(dateRaw);
                WellnessImport w = new WellnessImport(athleteId, athleteSquad, date,
                        metricGps(c, "WELL_SLEEP_SCORE"),
                        metricGps(c, "WELL_FATIGUE"),
                        metricGps(c, "WELL_SORENESS"),
                        metricGps(c, "WELL_STRESS"),
                        metricGps(c, "WELL_MOOD"),
                        plan.sourceSystem(),
                        recordId != null ? recordId : naturalKey("WELLNESS", athleteId, dateRaw, row.raw()),
                        batchId, syncedAt);
                yield ImportChunk.wellnessOnly(w);
            }
        };
    }

    private String value(Map<String, String> cells, String pseudoField) {
        MappingPlan.ColumnBinding b = plan.bindingFor(fieldHeader(pseudoField));
        if (b == null) {
            return null;
        }
        String v = cells.get(b.header());
        return v != null && !v.isBlank() ? v.trim() : null;
    }

    private String fieldHeader(String pseudoField) {
        return plan.bindings().stream()
                .filter(b -> pseudoField.equals(b.field()))
                .map(MappingPlan.ColumnBinding::header)
                .findFirst().orElse(null);
    }

    private String required(Map<String, String> cells, String pseudoField, String message) {
        String v = value(cells, pseudoField);
        if (v == null || v.isBlank()) {
            throw new RowViolationException(message);
        }
        return v;
    }

    /** Parse, unit-convert and range-validate one metric column. Named metricGps historically; generic. */
    private Double metricGps(Map<String, String> cells, String metricCode) {
        String header = fieldHeader(metricCode);
        if (header == null) {
            return null;
        }
        MappingPlan.ColumnBinding binding = plan.bindingFor(header);
        String raw = cells.get(header);
        Double parsed;
        try {
            parsed = ParsingSupport.parseDouble(raw);
        } catch (RowViolationException e) {
            throw new RowViolationException("column '" + header + "': " + e.getMessage());
        }
        if (parsed == null) {
            return null;
        }
        // unit conversion to canonical unit
        var def = dictionary.byCode(metricCode);
        if (def.isPresent() && binding.unit() != null
                && !binding.unit().equalsIgnoreCase(def.get().getUnit())) {
            try {
                parsed = UnitConverter.convert(parsed, binding.unit(), def.get().getUnit());
            } catch (IllegalArgumentException e) {
                throw new RowViolationException("column '" + header + "': " + e.getMessage());
            }
        }
        // physiological range validation
        if (def.isPresent() && !def.get().inRange(parsed)) {
            throw new RowViolationException("column '" + header + "': value " + parsed
                    + " outside valid range [" + def.get().getMinValid() + ", " + def.get().getMaxValid() + "]");
        }
        return parsed;
    }

    /** Deterministic natural key over the identity + instant + full raw row. */
    private static String naturalKey(String type, UUID athleteId, String when, String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((type + "|" + athleteId + "|" + when + "|" + raw)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
