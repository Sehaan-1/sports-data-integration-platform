package com.sdip.api;

import com.sdip.domain.entity.AthleteSourceLink;
import com.sdip.domain.entity.LinkQuarantine;
import com.sdip.domain.entity.LinkQuarantine.QuarantineStatus;
import com.sdip.domain.repo.AthleteSourceLinkRepository;
import com.sdip.domain.service.LinkAdministrationService;
import com.sdip.security.SdipPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin Quarantine Portal backend (§4.3): review, confirm, redirect, reject
 * candidate links; reversible unlinking. ADMIN only — these endpoints decide
 * identity for medical-adjacent longitudinal data.
 */
@RestController
@RequestMapping("/api/links")
@PreAuthorize("hasRole('ADMIN')")
public class LinkAdminController {

    private final LinkAdministrationService service;
    private final AthleteSourceLinkRepository links;

    public LinkAdminController(LinkAdministrationService service, AthleteSourceLinkRepository links) {
        this.service = service;
        this.links = links;
    }

    @GetMapping("/quarantine")
    public Page<LinkQuarantine> queue(@RequestParam(defaultValue = "PENDING") QuarantineStatus status,
                                      Pageable pageable) {
        return service.queue(status, pageable);
    }

    public record ResolveRequest(UUID athleteId, String note) {
    }

    public record ConfirmNewRequest(UUID squadId, String fullName, String note) {
    }

    @PostMapping("/quarantine/{id}/confirm")
    public AthleteSourceLink confirm(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest body) {
        SdipPrincipal p = CurrentUser.get();
        UUID overrideAthlete = body != null ? body.athleteId() : null;
        String note = body != null ? body.note() : null;
        return service.confirm(id, overrideAthlete, p.email(), p.role().name(), note);
    }

    @PostMapping("/quarantine/{id}/confirm-new-athlete")
    public AthleteSourceLink confirmNew(@PathVariable UUID id, @RequestBody ConfirmNewRequest body) {
        SdipPrincipal p = CurrentUser.get();
        return service.confirmNewAthlete(id, body != null ? body.squadId() : null,
                body != null ? body.fullName() : null, p.email(), p.role().name(),
                body != null ? body.note() : null);
    }

    @PostMapping("/quarantine/{id}/reject")
    public void reject(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest body) {
        SdipPrincipal p = CurrentUser.get();
        service.reject(id, p.email(), p.role().name(), body != null ? body.note() : null);
    }

    public record LinkDto(String id, String athleteId, String athleteName, String sourceSystem,
                          String sourceAthleteId, String matchTier, Double matchScore,
                          String status, String linkedBy, String linkedAt) {
    }

    @GetMapping("/athlete/{athleteId}")
    @Transactional(readOnly = true)
    public List<LinkDto> linksFor(@PathVariable UUID athleteId) {
        return links.findByAthleteIdAndStatus(athleteId, AthleteSourceLink.LinkStatus.ACTIVE).stream()
                .map(l -> new LinkDto(l.getId().toString(), l.getAthlete().getId().toString(),
                        l.getAthlete().getFullName(), l.getSourceSystem(), l.getSourceAthleteId(),
                        l.getMatchTier().name(), l.getMatchScore(), l.getStatus().name(),
                        l.getLinkedBy(), l.getLinkedAt().toString()))
                .toList();
    }

    /** Reversible unlink — never destructive (§4.3, R3 mitigation). */
    @DeleteMapping("/{linkId}")
    public void unlink(@PathVariable UUID linkId) {
        SdipPrincipal p = CurrentUser.get();
        service.unlink(linkId, p.email(), p.role().name());
    }
}
