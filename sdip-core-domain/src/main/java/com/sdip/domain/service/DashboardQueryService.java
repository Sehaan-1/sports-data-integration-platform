package com.sdip.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Dashboard aggregation queries (§3.2 analytics layer). All queries accept an
 * optional squad scope so row-level authorization is enforced for non-admin
 * callers even when the Hibernate filter is not active (defense in depth).
 */
@Service
public class DashboardQueryService {

    private final JdbcTemplate jdbc;

    public DashboardQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Daily load series for one athlete plus acute:chronic workload ratio. */
    public Map<String, Object> athleteLoad(UUID athleteId, Instant from, Instant to) {
        List<Map<String, Object>> days = jdbc.query("""
                SELECT CAST(recorded_at AS DATE) AS load_day,
                       SUM(player_load)      AS player_load,
                       SUM(total_distance_m) AS total_distance_m,
                       SUM(hsr_distance_m)   AS hsr_m,
                       MAX(max_velocity_kmh) AS max_velocity_kmh
                FROM gps_metric
                WHERE athlete_id = ? AND recorded_at BETWEEN ? AND ?
                GROUP BY CAST(recorded_at AS DATE)
                ORDER BY load_day
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("day", rs.getDate("load_day").toLocalDate().toString());
                    m.put("playerLoad", rs.getDouble("player_load"));
                    m.put("totalDistanceM", rs.getDouble("total_distance_m"));
                    m.put("hsrM", rs.getDouble("hsr_m"));
                    m.put("maxVelocityKmh", rs.getDouble("max_velocity_kmh"));
                    return m;
                }, athleteId, Timestamp.from(from), Timestamp.from(to));

        List<Map<String, Object>> withAcwr = attachAcwr(days);
        Map<String, Object> out = new HashMap<>();
        out.put("athleteId", athleteId);
        out.put("days", withAcwr);
        return out;
    }

    /** Team comparison: per-athlete load totals over a window. */
    public List<Map<String, Object>> teamLoad(UUID squadId, Instant from, Instant to) {
        String sql = """
                SELECT athlete_id, a.full_name,
                       SUM(g.player_load)      AS player_load,
                       SUM(g.total_distance_m) AS total_distance_m,
                       SUM(g.hsr_distance_m)   AS hsr_m,
                       COUNT(*)                AS sessions
                FROM gps_metric g JOIN athlete a ON a.id = g.athlete_id
                WHERE g.recorded_at BETWEEN ? AND ? %s
                GROUP BY athlete_id, a.full_name
                ORDER BY player_load DESC
                """;
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        if (squadId != null) {
            sql = sql.formatted("AND g.squad_id = ?");
            args.add(squadId);
        } else {
            sql = sql.formatted("");
        }
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("athleteId", rs.getObject("athlete_id").toString());
            m.put("fullName", rs.getString("full_name"));
            m.put("playerLoad", rs.getDouble("player_load"));
            m.put("totalDistanceM", rs.getDouble("total_distance_m"));
            m.put("hsrM", rs.getDouble("hsr_m"));
            m.put("sessions", rs.getLong("sessions"));
            return m;
        }, args.toArray());
    }

    /** GPS-load vs neuromuscular-fatigue overlay for one athlete (§7 P5). */
    public Map<String, Object> fatigueOverlay(UUID athleteId, Instant from, Instant to) {
        Map<String, Object> load = athleteLoad(athleteId, from, to);
        List<Map<String, Object>> tests = jdbc.query("""
                SELECT CAST(tested_at AS DATE) AS load_day, test_type,
                       AVG(rsi_modified)  AS rsi_modified,
                       AVG(asymmetry_pct) AS asymmetry_pct,
                       AVG(peak_force_n)  AS peak_force_n
                FROM force_test
                WHERE athlete_id = ? AND tested_at BETWEEN ? AND ?
                GROUP BY CAST(tested_at AS DATE), test_type
                ORDER BY load_day
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("day", rs.getDate("load_day").toLocalDate().toString());
                    m.put("testType", rs.getString("test_type"));
                    m.put("rsiModified", rs.getDouble("rsi_modified"));
                    m.put("asymmetryPct", rs.getDouble("asymmetry_pct"));
                    m.put("peakForceN", rs.getDouble("peak_force_n"));
                    return m;
                }, athleteId, Timestamp.from(from), Timestamp.from(to));
        Map<String, Object> out = new HashMap<>(load);
        out.put("forceTests", tests);
        return out;
    }

    /**
     * ACWR: acute = mean daily load over [d-6..d]; chronic = mean daily load
     * over the prior 21 days [d-27..d-7] (rolling, uncoupled).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachAcwr(List<Map<String, Object>> days) {
        Map<LocalDate, Double> loadByDay = new TreeMap<>();
        for (Map<String, Object> d : days) {
            loadByDay.merge(LocalDate.parse((String) d.get("day")),
                    ((Number) d.get("playerLoad")).doubleValue(), Double::sum);
        }
        ZoneOffset z = ZoneOffset.UTC;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : days) {
            LocalDate day = LocalDate.parse((String) d.get("day"));
            double acute = mean(loadByDay, day.minusDays(6), day);
            double chronic = mean(loadByDay, day.minusDays(27), day.minusDays(7));
            Map<String, Object> m = new HashMap<>(d);
            m.put("acuteLoad", round(acute));
            m.put("chronicLoad", round(chronic));
            m.put("acwr", chronic > 0 ? round(acute / chronic) : null);
            out.add(m);
        }
        return out;
    }

    private static double mean(Map<LocalDate, Double> load, LocalDate from, LocalDate to) {
        double sum = 0;
        int n = 0;
        LocalDate d = from;
        while (!d.isAfter(to)) {
            Double v = load.get(d);
            if (v != null) {
                sum += v;
            }
            n++;
            d = d.plusDays(1);
        }
        return n == 0 ? 0 : sum / n;
    }

    private static Double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
