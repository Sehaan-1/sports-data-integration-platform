package com.sdip.api;

import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.service.AuditService;
import com.sdip.domain.service.RowScopeGuard;
import com.sdip.security.SdipPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CSV exports — every export emits exactly one audit event (RQ3 criterion).
 * Staff roles only; the read-only analyst cannot exfiltrate bulk data.
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final AthleteRepository athletes;
    private final AuditService audit;
    private final RowScopeGuard scope;

    public ExportController(AthleteRepository athletes, AuditService audit, RowScopeGuard scope) {
        this.athletes = athletes;
        this.audit = audit;
        this.scope = scope;
    }

    @GetMapping("/athletes.csv")
    @PreAuthorize("hasAnyRole('ADMIN','HEAD_COACH')")
    @Transactional(readOnly = true)
    public ResponseEntity<String> athletesCsv() {
        scope.enableForCurrentPrincipal();
        StringBuilder csv = new StringBuilder("id,full_name,dob,email,gender,provisional,squad\n");
        final int[] count = {0};
        athletes.findAll().forEach(a -> {
            csv.append(a.getId()).append(',')
                    .append(escape(a.getFullName())).append(',')
                    .append(a.getDob() != null ? a.getDob() : "").append(',')
                    .append(a.getEmail() != null ? a.getEmail() : "").append(',')
                    .append(a.getGender() != null ? a.getGender() : "").append(',')
                    .append(a.isProvisional()).append(',')
                    .append(escape(a.getSquad().getName())).append('\n');
            count[0]++;
        });
        SdipPrincipal p = CurrentUser.get();
        audit.record(p.email(), p.role().name(), "EXPORT_DATA", "Athlete", null,
                Map.of("format", "csv", "rows", count[0]));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=athletes.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
