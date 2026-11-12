package com.sdip.domain.service;

import com.sdip.domain.service.ImportRecords.ForceImport;
import com.sdip.domain.service.ImportRecords.GpsImport;
import com.sdip.domain.service.ImportRecords.WellnessImport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Idempotent canonical writer (C4 / §6). Inserts and updates are split per
 * chunk after one set-membership query on the natural key
 * (source_system, source_record_id), using portable SQL so the identical
 * code path runs on PostgreSQL 16 and the H2 test rig. Re-running any sync
 * or file ingest therefore produces zero duplicates; late-arriving vendor
 * edits overwrite measurement columns and refresh {@code last_vendor_sync}.
 */
@Service
public class CanonicalImportService {

    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public CanonicalImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * This writer bypasses Hibernate (raw JDBC for bulk throughput). Any JPA
     * state created upstream in the SAME transaction — linked athletes, audit
     * rows — must flush first, otherwise foreign keys to not-yet-inserted
     * athlete/link rows fail. (Found by the FK violation in CsvIngestionFlowTest.)
     */
    private void flushPendingJpaWrites() {
        try {
            if (entityManager != null && entityManager.isJoinedToTransaction()) {
                entityManager.flush();
            }
        } catch (Exception ignored) {
            // no active JPA session (plain JDBC context) — nothing to flush
        }
    }

    @Transactional
    public int upsertGps(List<GpsImport> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        flushPendingJpaWrites();
        for (List<GpsImport> group : groupBySource(rows).values()) {
            Set<String> existing = existingKeys("gps_metric", group.stream().map(GpsImport::sourceRecordId).toList(), group.get(0).sourceSystem());
            List<GpsImport> inserts = group.stream().filter(r -> !existing.contains(r.sourceRecordId())).toList();
            List<GpsImport> updates = group.stream().filter(r -> existing.contains(r.sourceRecordId())).toList();

            jdbc.batchUpdate("""
                            INSERT INTO gps_metric (id, athlete_id, squad_id, recorded_at, total_distance_m,
                                hsr_distance_m, player_load, max_velocity_kmh, session_minutes,
                                source_system, source_record_id, ingestion_batch_id, ingested_at, last_vendor_sync)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                    inserts, inserts.size(),
                    (ps, r) -> {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, r.athleteId());
                        ps.setObject(3, r.squadId());
                        ps.setTimestamp(4, Timestamp.from(r.recordedAt()));
                        setDoubles(ps, 5, r.totalDistanceM(), r.hsrDistanceM(), r.playerLoad(), r.maxVelocityKmh(), r.sessionMinutes());
                        ps.setString(10, r.sourceSystem());
                        ps.setString(11, r.sourceRecordId());
                        ps.setObject(12, r.batchId());
                        ps.setTimestamp(13, Timestamp.from(Instant.now()));
                        ps.setTimestamp(14, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : null);
                    });

            jdbc.batchUpdate("""
                            UPDATE gps_metric SET athlete_id=?, squad_id=?, recorded_at=?, total_distance_m=?,
                                hsr_distance_m=?, player_load=?, max_velocity_kmh=?, session_minutes=?,
                                ingestion_batch_id=?, last_vendor_sync=?
                            WHERE source_system=? AND source_record_id=?
                            """,
                    updates, updates.size(),
                    (ps, r) -> {
                        ps.setObject(1, r.athleteId());
                        ps.setObject(2, r.squadId());
                        ps.setTimestamp(3, Timestamp.from(r.recordedAt()));
                        setDoubles(ps, 4, r.totalDistanceM(), r.hsrDistanceM(), r.playerLoad(), r.maxVelocityKmh(), r.sessionMinutes());
                        ps.setObject(9, r.batchId());
                        ps.setTimestamp(10, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : Timestamp.from(Instant.now()));
                        ps.setString(11, r.sourceSystem());
                        ps.setString(12, r.sourceRecordId());
                    });
        }
        return rows.size();
    }

    @Transactional
    public int upsertForce(List<ForceImport> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        flushPendingJpaWrites();
        for (List<ForceImport> group : groupBySource(rows).values()) {
            Set<String> existing = existingKeys("force_test", group.stream().map(ForceImport::sourceRecordId).toList(), group.get(0).sourceSystem());
            List<ForceImport> inserts = group.stream().filter(r -> !existing.contains(r.sourceRecordId())).toList();
            List<ForceImport> updates = group.stream().filter(r -> existing.contains(r.sourceRecordId())).toList();

            jdbc.batchUpdate("""
                            INSERT INTO force_test (id, athlete_id, squad_id, test_type, tested_at, peak_force_n,
                                asymmetry_pct, rsi_modified, peak_power_w,
                                source_system, source_record_id, ingestion_batch_id, ingested_at, last_vendor_sync)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                    inserts, inserts.size(),
                    (ps, r) -> {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, r.athleteId());
                        ps.setObject(3, r.squadId());
                        ps.setString(4, r.testType());
                        ps.setTimestamp(5, Timestamp.from(r.testedAt()));
                        setDoubles(ps, 6, r.peakForceN(), r.asymmetryPct(), r.rsiModified(), r.peakPowerW());
                        ps.setString(10, r.sourceSystem());
                        ps.setString(11, r.sourceRecordId());
                        ps.setObject(12, r.batchId());
                        ps.setTimestamp(13, Timestamp.from(Instant.now()));
                        ps.setTimestamp(14, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : null);
                    });

            jdbc.batchUpdate("""
                            UPDATE force_test SET athlete_id=?, squad_id=?, test_type=?, tested_at=?, peak_force_n=?,
                                asymmetry_pct=?, rsi_modified=?, peak_power_w=?,
                                ingestion_batch_id=?, last_vendor_sync=?
                            WHERE source_system=? AND source_record_id=?
                            """,
                    updates, updates.size(),
                    (ps, r) -> {
                        ps.setObject(1, r.athleteId());
                        ps.setObject(2, r.squadId());
                        ps.setString(3, r.testType());
                        ps.setTimestamp(4, Timestamp.from(r.testedAt()));
                        setDoubles(ps, 5, r.peakForceN(), r.asymmetryPct(), r.rsiModified(), r.peakPowerW());
                        ps.setObject(9, r.batchId());
                        ps.setTimestamp(10, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : Timestamp.from(Instant.now()));
                        ps.setString(11, r.sourceSystem());
                        ps.setString(12, r.sourceRecordId());
                    });
        }
        return rows.size();
    }

    @Transactional
    public int upsertWellness(List<WellnessImport> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        flushPendingJpaWrites();
        for (List<WellnessImport> group : groupBySource(rows).values()) {
            Set<String> existing = existingKeys("wellness_survey", group.stream().map(WellnessImport::sourceRecordId).toList(), group.get(0).sourceSystem());
            List<WellnessImport> inserts = group.stream().filter(r -> !existing.contains(r.sourceRecordId())).toList();
            List<WellnessImport> updates = group.stream().filter(r -> existing.contains(r.sourceRecordId())).toList();

            jdbc.batchUpdate("""
                            INSERT INTO wellness_survey (id, athlete_id, squad_id, survey_date, sleep_score,
                                fatigue, soreness, stress, mood,
                                source_system, source_record_id, ingestion_batch_id, ingested_at, last_vendor_sync)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                    inserts, inserts.size(),
                    (ps, r) -> {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, r.athleteId());
                        ps.setObject(3, r.squadId());
                        ps.setDate(4, Date.valueOf(r.surveyDate()));
                        setDoubles(ps, 5, r.sleepScore(), r.fatigue(), r.soreness(), r.stress(), r.mood());
                        ps.setString(10, r.sourceSystem());
                        ps.setString(11, r.sourceRecordId());
                        ps.setObject(12, r.batchId());
                        ps.setTimestamp(13, Timestamp.from(Instant.now()));
                        ps.setTimestamp(14, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : null);
                    });

            jdbc.batchUpdate("""
                            UPDATE wellness_survey SET athlete_id=?, squad_id=?, survey_date=?, sleep_score=?,
                                fatigue=?, soreness=?, stress=?, mood=?,
                                ingestion_batch_id=?, last_vendor_sync=?
                            WHERE source_system=? AND source_record_id=?
                            """,
                    updates, updates.size(),
                    (ps, r) -> {
                        ps.setObject(1, r.athleteId());
                        ps.setObject(2, r.squadId());
                        ps.setDate(3, Date.valueOf(r.surveyDate()));
                        setDoubles(ps, 4, r.sleepScore(), r.fatigue(), r.soreness(), r.stress(), r.mood());
                        ps.setObject(9, r.batchId());
                        ps.setTimestamp(10, r.syncedAt() != null ? Timestamp.from(r.syncedAt()) : Timestamp.from(Instant.now()));
                        ps.setString(11, r.sourceSystem());
                        ps.setString(12, r.sourceRecordId());
                    });
        }
        return rows.size();
    }

    private static void setDoubles(java.sql.PreparedStatement ps, int start, Double... values)
            throws java.sql.SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                ps.setObject(start + i, null);
            } else {
                ps.setDouble(start + i, values[i]);
            }
        }
    }

    /**
     * One set-membership query per chunk: which natural keys already exist?
     * (Portable alternative to vendor-specific upsert syntax.)
     */
    private Set<String> existingKeys(String table, List<String> recordIds, String sourceSystem) {
        if (recordIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(recordIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(sourceSystem);
        args.addAll(recordIds);
        List<String> found = jdbc.queryForList(
                "SELECT source_record_id FROM " + table + " WHERE source_system = ? AND source_record_id IN (" + placeholders + ")",
                String.class, args.toArray());
        return new HashSet<>(found);
    }

    private static <T> Map<String, List<T>> groupBySource(List<T> rows) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T row : rows) {
            String source = switch (row) {
                case GpsImport g -> g.sourceSystem();
                case ForceImport f -> f.sourceSystem();
                case WellnessImport w -> w.sourceSystem();
                default -> throw new IllegalArgumentException("unknown import record: " + row.getClass());
            };
            groups.computeIfAbsent(source, k -> new ArrayList<>()).add(row);
        }
        return groups;
    }
}
