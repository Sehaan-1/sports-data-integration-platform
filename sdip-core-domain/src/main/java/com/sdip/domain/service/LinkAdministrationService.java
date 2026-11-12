package com.sdip.domain.service;

import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.AthleteSourceLink;
import com.sdip.domain.entity.AthleteSourceLink.LinkStatus;
import com.sdip.domain.entity.AthleteSourceLink.MatchTier;
import com.sdip.domain.entity.LinkQuarantine;
import com.sdip.domain.entity.LinkQuarantine.QuarantineStatus;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.AthleteSourceLinkRepository;
import com.sdip.domain.repo.LinkQuarantineRepository;
import com.sdip.domain.repo.SquadRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Human-in-the-loop resolution of quarantined link candidates (§4.3):
 * confirm (optionally redirecting to another athlete), confirm-new-athlete,
 * or reject — plus reversible unlinking. Every decision is audited.
 */
@Service
public class LinkAdministrationService {

    private final LinkQuarantineRepository quarantine;
    private final AthleteSourceLinkRepository links;
    private final AthleteRepository athletes;
    private final SquadRepository squads;
    private final AuditService audit;

    public LinkAdministrationService(LinkQuarantineRepository quarantine,
                                     AthleteSourceLinkRepository links,
                                     AthleteRepository athletes,
                                     SquadRepository squads,
                                     AuditService audit) {
        this.quarantine = quarantine;
        this.links = links;
        this.athletes = athletes;
        this.squads = squads;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<LinkQuarantine> queue(QuarantineStatus status, Pageable pageable) {
        return quarantine.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    /**
     * Confirm a quarantined pair, optionally redirecting to a different
     * athlete than the heuristic candidate proposed.
     */
    @Transactional
    public AthleteSourceLink confirm(UUID quarantineId, UUID athleteIdOverride,
                                     String actorEmail, String actorRole, String note) {
        LinkQuarantine item = pending(quarantineId);
        UUID athleteId = athleteIdOverride != null ? athleteIdOverride : item.getCandidateAthleteId();
        if (athleteId == null) {
            throw new IllegalStateException("no candidate athlete; use confirm-new-athlete or reject");
        }
        Athlete athlete = athletes.findById(athleteId)
                .orElseThrow(() -> new EntityNotFoundException("athlete " + athleteId));
        return attach(item, athlete, actorEmail, actorRole, note);
    }

    /** When the identity belongs to no existing athlete: create a fresh master record and link it. */
    @Transactional
    public AthleteSourceLink confirmNewAthlete(UUID quarantineId, UUID squadId, String fullName,
                                               String actorEmail, String actorRole, String note) {
        LinkQuarantine item = pending(quarantineId);
        UUID resolvedSquad = squadId;
        if (resolvedSquad == null && item.getCandidateAthleteId() != null) {
            resolvedSquad = athletes.findById(item.getCandidateAthleteId())
                    .map(a -> a.getSquad().getId()).orElse(null);
        }
        if (resolvedSquad == null) {
            resolvedSquad = squads.findAll().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("no squad configured")).getId();
        }
        final UUID effectiveSquad = resolvedSquad;
        Squad squad = squads.findById(effectiveSquad)
                .orElseThrow(() -> new EntityNotFoundException("squad " + effectiveSquad));
        Athlete fresh = new Athlete(squad, fullName != null ? fullName : "Unknown Athlete",
                null, null, false);
        athletes.save(fresh);
        return attach(item, fresh, actorEmail, actorRole,
                (note != null ? note : "") + " [created athlete " + fresh.getId() + "]");
    }

    @Transactional
    public void reject(UUID quarantineId, String actorEmail, String actorRole, String note) {
        LinkQuarantine item = pending(quarantineId);
        item.resolve(QuarantineStatus.REJECTED, actorEmail, note);
        audit.record(actorEmail, actorRole, "LINK_REJECT", "LinkQuarantine", quarantineId.toString(),
                Map.of("sourceSystem", item.getSourceSystem(),
                        "sourceAthleteId", item.getSourceAthleteId(), "note", note != null ? note : ""));
    }

    /** Reversible unlink — merges are never destructive (§4.3). */
    @Transactional
    public void unlink(UUID linkId, String actorEmail, String actorRole) {
        AthleteSourceLink link = links.findById(linkId)
                .orElseThrow(() -> new EntityNotFoundException("link " + linkId));
        if (link.getStatus() == LinkStatus.REVOKED) {
            return;
        }
        link.revoke();
        audit.record(actorEmail, actorRole, "LINK_REVOKE", "AthleteSourceLink", linkId.toString(),
                Map.of("sourceSystem", link.getSourceSystem(),
                        "athleteId", link.getAthlete().getId().toString()));
    }

    private LinkQuarantine pending(UUID quarantineId) {
        LinkQuarantine item = quarantine.findById(quarantineId)
                .orElseThrow(() -> new EntityNotFoundException("quarantine item " + quarantineId));
        if (item.getStatus() != QuarantineStatus.PENDING) {
            throw new IllegalStateException("quarantine item already resolved: " + item.getStatus());
        }
        return item;
    }

    private AthleteSourceLink attach(LinkQuarantine item, Athlete athlete,
                                     String actorEmail, String actorRole, String note) {
        // If the source identity is already linked to someone else, revoke first.
        links.findBySourceSystemAndSourceAthleteIdAndStatus(
                        item.getSourceSystem(), item.getSourceAthleteId(), LinkStatus.ACTIVE)
                .ifPresent(existing -> {
                    if (!existing.getAthlete().getId().equals(athlete.getId())) {
                        existing.revoke();
                        audit.record(actorEmail, actorRole, "LINK_REVOKE", "AthleteSourceLink",
                                existing.getId().toString(), Map.of("reason", "superseded by quarantine resolution"));
                    }
                });

        AthleteSourceLink link = new AthleteSourceLink(athlete, item.getSourceSystem(),
                item.getSourceAthleteId(), MatchTier.HUMAN_CONFIRMED, item.getScore(), actorEmail);
        links.save(link);
        item.resolve(QuarantineStatus.CONFIRMED, actorEmail, note);
        audit.record(actorEmail, actorRole, "LINK_CONFIRM", "AthleteSourceLink", link.getId().toString(),
                Map.of("sourceSystem", item.getSourceSystem(),
                        "sourceAthleteId", item.getSourceAthleteId(),
                        "athleteId", athlete.getId().toString(),
                        "quarantineId", item.getId().toString()));
        return link;
    }
}
