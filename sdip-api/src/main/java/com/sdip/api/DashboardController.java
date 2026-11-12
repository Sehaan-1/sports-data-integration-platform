package com.sdip.api;

import com.sdip.domain.entity.Athlete;
import com.sdip.domain.service.DashboardQueryService;
import com.sdip.domain.service.RowScopeGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consolidated dashboard read endpoints (§3.2). Available to every
 * authenticated role; the squad restriction is ALWAYS resolved server-side
 * and applied to the SQL (JdbcTemplate is outside Hibernate filters, so the
 * explicit parameter is the enforcement — defense in depth).
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("isAuthenticated()")
public class DashboardController {

    private final DashboardQueryService queries;
    private final AthleteService athletes;
    private final RowScopeGuard scope;

    public DashboardController(DashboardQueryService queries, AthleteService athletes, RowScopeGuard scope) {
        this.queries = queries;
        this.athletes = athletes;
        this.scope = scope;
    }

    public record Window(Instant from, Instant to) {
        static Window of(Integer daysBack) {
            int days = daysBack != null && daysBack > 0 ? Math.min(daysBack, 366) : 42;
            Instant to = Instant.now();
            return new Window(to.minus(days, ChronoUnit.DAYS), to);
        }
    }

    @GetMapping("/athlete/{id}/load")
    public Map<String, Object> athleteLoad(@PathVariable UUID id,
                                           @RequestParam(required = false) Integer days) {
        athletes.requireScopedEntity(id); // 404 on cross-tenant access
        Window w = Window.of(days);
        return queries.athleteLoad(id, w.from(), w.to());
    }

    @GetMapping("/athlete/{id}/fatigue-overlay")
    public Map<String, Object> fatigueOverlay(@PathVariable UUID id,
                                              @RequestParam(required = false) Integer days) {
        Athlete a = athletes.requireScopedEntity(id);
        Window w = Window.of(days);
        Map<String, Object> overlay = queries.fatigueOverlay(id, w.from(), w.to());
        overlay.put("athleteName", a.getFullName());
        return overlay;
    }

    @GetMapping("/team/load")
    public List<Map<String, Object>> teamLoad(@RequestParam(required = false) UUID squadId,
                                              @RequestParam(required = false) Integer days) {
        UUID restriction = scope.restriction();
        UUID effectiveSquad = restriction != null ? restriction : squadId; // server-side scope wins (C3)
        Window w = Window.of(days);
        return queries.teamLoad(effectiveSquad, w.from(), w.to());
    }
}
