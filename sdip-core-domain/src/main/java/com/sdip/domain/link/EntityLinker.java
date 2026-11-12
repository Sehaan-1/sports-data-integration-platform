package com.sdip.domain.link;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sdip.domain.service.AuditService;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tiered athlete record-linkage pipeline (§4.3):
 * <ol>
 *   <li><b>Tier 1 (deterministic)</b>: previously stored vendor identity, or
 *       exact verified-email match → auto-link at score 1.0.</li>
 *   <li><b>Tier 2 (heuristic)</b>: Jaro–Winkler name similarity + Double
 *       Metaphone phonetic agreement + DOB agreement, with DOB-year blocking.
 *       ≥ τ_high auto-link; [τ_low, τ_high) quarantine; &lt; τ_low new provisional athlete.</li>
 * </ol>
 * Thresholds are empirically tuned on the labeled synthetic corpus (§8 RQ2)
 * and externalised via {@code sdip.linking.tau-high} / {@code tau-low}.
 */
@Service
public class EntityLinker {

    private static final Logger log = LoggerFactory.getLogger(EntityLinker.class);
    private static final String SYSTEM_ACTOR = "system";

    private final AthleteRepository athletes;
    private final AthleteSourceLinkRepository links;
    private final LinkQuarantineRepository quarantine;
    private final SquadRepository squads;
    private final AuditService audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();
    private final DoubleMetaphone metaphone = new DoubleMetaphone();

    private final double tauHigh;
    private final double tauLow;

    /** Composite score weights — name, phonetic, DOB. */
    private static final double W_NAME = 0.52;
    private static final double W_PHONETIC = 0.14;
    private static final double W_DOB = 0.34;

    public EntityLinker(AthleteRepository athletes,
                        AthleteSourceLinkRepository links,
                        LinkQuarantineRepository quarantine,
                        SquadRepository squads,
                        AuditService audit,
                        @Value("${sdip.linking.tau-high:0.90}") double tauHigh,
                        @Value("${sdip.linking.tau-low:0.68}") double tauLow) {
        this.athletes = athletes;
        this.links = links;
        this.quarantine = quarantine;
        this.squads = squads;
        this.audit = audit;
        this.tauHigh = tauHigh;
        this.tauLow = tauLow;
    }

    /**
     * Route one observed identity. Idempotent: re-presenting a previously
     * linked or quarantined (source_system, source_athlete_id) returns the
     * existing decision with no new database writes.
     */
    @Transactional
    public LinkOutcome resolve(IdentityRecord id) {
        // Tier 1a — deterministic re-confirmation via stored vendor identity
        if (id.sourceAthleteId() != null) {
            Optional<AthleteSourceLink> existing = links.findBySourceSystemAndSourceAthleteIdAndStatus(
                    id.sourceSystem(), id.sourceAthleteId(), LinkStatus.ACTIVE);
            if (existing.isPresent()) {
                return LinkOutcome.linked(existing.get().getAthlete().getId(), 1.0);
            }
            // Idempotent quarantine: do not flood the queue on re-ingestion
            Optional<LinkQuarantine> pending = findPendingQuarantine(id);
            if (pending.isPresent()) {
                return LinkOutcome.quarantined(pending.get().getId(), pending.get().getScore());
            }
        }

        // Tier 1b — deterministic exact email, scoped to the record's squad
        // (email is unique per squad; never match across row-scope boundaries).
        if (id.email() != null && !id.email().isBlank()) {
            String email = id.email().trim();
            Optional<Athlete> byEmail = id.defaultSquadId() != null
                    ? athletes.findFirstByEmailIgnoreCaseAndSquad_Id(email, id.defaultSquadId())
                    : athletes.findByEmailIgnoreCase(email);
            if (byEmail.isPresent() && sameTenant(byEmail.get(), id)) {
                Athlete a = byEmail.get();
                createLink(a, id, MatchTier.DETERMINISTIC, 1.0);
                return LinkOutcome.linked(a.getId(), 1.0);
            }
        }

        // Tier 2 — heuristic scoring with DOB-year blocking
        Candidate best = bestCandidate(id);
        if (best != null && best.score >= tauHigh) {
            createLink(best.athlete, id, MatchTier.HEURISTIC, best.score);
            return LinkOutcome.linked(best.athlete.getId(), best.score);
        }
        if (best != null && best.score >= tauLow) {
            UUID qid = createQuarantine(id, best);
            return LinkOutcome.quarantined(qid, best.score);
        }

        // Below the floor: provisional new athlete, linked so re-ingestion is stable
        Athlete fresh = createProvisional(id);
        if (id.sourceAthleteId() != null) {
            createLink(fresh, id, MatchTier.DETERMINISTIC, 0.0);
        }
        return LinkOutcome.provisional(fresh.getId());
    }

    /** Visible for the RQ2 evaluation harness: score a specific candidate pair. */
    public double score(IdentityRecord probe, String candidateName, LocalDate candidateDob) {
        return scoreAgainst(probe, candidateName, candidateDob);
    }

    private record Candidate(Athlete athlete, double score) {
    }

    private Candidate bestCandidate(IdentityRecord id) {
        List<Athlete> blocking;
        if (id.dob() != null) {
            blocking = athletes.findByBirthYear(id.dob().getYear());
        } else {
            blocking = athletes.findAll(PageRequest.of(0, 5000)).getContent();
        }
        Candidate best = null;
        Candidate secondBest = null;
        for (Athlete a : blocking) {
            if (!sameTenant(a, id)) {
                continue; // C3: never leak identities across squad/tenant boundaries
            }
            double s = scoreAgainst(id, a.getFullName(), a.getDob());
            if (best == null || s > best.score) {
                secondBest = best;
                best = new Candidate(a, s);
            } else if (secondBest == null || s > secondBest.score) {
                secondBest = new Candidate(a, s);
            }
        }
        // Ambiguity guard: two near-tied high scorers must go to a human, never to a
        // coin flip — false merges are the catastrophic error class (§2 C2).
        if (best != null && secondBest != null
                && best.score >= tauHigh && secondBest.score >= tauHigh
                && (best.score - secondBest.score) < 0.02) {
            return new Candidate(best.athlete, Math.min(best.score, tauHigh - 0.001));
        }
        return best;
    }

    private static boolean sameTenant(Athlete candidate, IdentityRecord id) {
        if (id.defaultSquadId() == null || candidate.getSquad() == null) {
            return true;
        }
        return id.defaultSquadId().equals(candidate.getSquad().getId());
    }

    private double scoreAgainst(IdentityRecord probe, String candidateName, LocalDate candidateDob) {
        double nameSim = 0.0;
        if (probe.fullName() != null && candidateName != null) {
            Double jw = jaroWinkler.apply(Names.normalize(probe.fullName()), Names.normalize(candidateName));
            nameSim = jw != null ? jw : 0.0;
        }

        double phonetic = 0.0;
        if (probe.fullName() != null && candidateName != null) {
            String c1 = metaphone.doubleMetaphone(Names.normalize(probe.fullName()));
            String c2 = metaphone.doubleMetaphone(Names.normalize(candidateName));
            phonetic = (!c1.isEmpty() && c1.equals(c2)) ? 1.0 : 0.0;
        }

        double dobScore;
        if (probe.dob() == null || candidateDob == null) {
            dobScore = 0.5; // neutral when unknown on either side
        } else {
            long days = Math.abs(ChronoUnit.DAYS.between(probe.dob(), candidateDob));
            if (days == 0) {
                dobScore = 1.0;
            } else if (days <= 1) {
                dobScore = 0.55; // classic off-by-one transcription error
            } else if (probe.dob().getYear() == candidateDob.getYear()
                    && probe.dob().getMonth() == candidateDob.getMonth()) {
                dobScore = 0.35;
            } else if (probe.dob().getYear() == candidateDob.getYear()) {
                dobScore = 0.15;
            } else {
                dobScore = 0.0;
            }
        }
        return W_NAME * nameSim + W_PHONETIC * phonetic + W_DOB * dobScore;
    }

    private void createLink(Athlete athlete, IdentityRecord id, MatchTier tier, double score) {
        if (id.sourceAthleteId() == null) {
            return; // nothing stable to bind
        }
        AthleteSourceLink link = new AthleteSourceLink(
                athlete, id.sourceSystem(), id.sourceAthleteId(), tier, score, SYSTEM_ACTOR);
        // Flush immediately: bulk JDBC writers may reference this row via FK in
        // the same transaction, before Hibernate would auto-flush.
        links.saveAndFlush(link);
        audit.record(SYSTEM_ACTOR, "SYSTEM", "LINK_CREATE", "AthleteSourceLink", link.getId().toString(),
                Map.of("sourceSystem", id.sourceSystem(),
                        "sourceAthleteId", id.sourceAthleteId(),
                        "athleteId", athlete.getId().toString(),
                        "tier", tier.name(),
                        "score", score));
        log.debug("auto-linked {}:{} -> athlete {} ({} score={})", id.sourceSystem(), id.sourceAthleteId(),
                athlete.getId(), tier, String.format("%.3f", score));
    }

    private UUID createQuarantine(IdentityRecord id, Candidate best) {
        String payloadJson;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("fullName", id.fullName());
            payload.put("dob", id.dob() != null ? id.dob().toString() : null);
            payload.put("email", id.email());
            payload.put("candidateName", best.athlete.getFullName());
            payload.put("candidateDob", best.athlete.getDob() != null ? best.athlete.getDob().toString() : null);
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            payloadJson = "{}";
        }
        LinkQuarantine q = new LinkQuarantine(id.sourceSystem(),
                id.sourceAthleteId() != null ? id.sourceAthleteId() : "none",
                best.athlete.getId(), best.score, payloadJson);
        quarantine.save(q);
        audit.record(SYSTEM_ACTOR, "SYSTEM", "LINK_QUARANTINE", "LinkQuarantine", q.getId().toString(),
                Map.of("sourceSystem", id.sourceSystem(), "score", best.score));
        return q.getId();
    }

    private Athlete createProvisional(IdentityRecord id) {
        Squad squad = null;
        if (id.defaultSquadId() != null) {
            squad = squads.findById(id.defaultSquadId()).orElse(null);
        }
        if (squad == null) {
            squad = squads.findAll(PageRequest.of(0, 1)).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No squad exists for provisional athlete creation"));
        }
        Athlete a = new Athlete(squad,
                id.fullName() != null ? id.fullName() : "Unknown Athlete",
                id.dob(),
                // Persist the observed email: without it the deterministic Tier-1b
                // email match can never re-find this provisional athlete, and the
                // same person from a second vendor would quarantine forever.
                id.email() != null && !id.email().isBlank() ? id.email().trim() : null,
                true);
        // saveAndFlush: a same-transaction bulk JDBC writer inserts fact rows
        // referencing this athlete via FK before auto-flush would occur.
        return athletes.saveAndFlush(a);
    }

    private Optional<LinkQuarantine> findPendingQuarantine(IdentityRecord id) {
        return quarantine.findByStatusOrderByCreatedAtDesc(QuarantineStatus.PENDING, PageRequest.of(0, 5000))
                .stream()
                .filter(q -> q.getSourceSystem().equals(id.sourceSystem())
                        && q.getSourceAthleteId().equals(id.sourceAthleteId()))
                .findFirst();
    }

    public double tauHigh() {
        return tauHigh;
    }

    public double tauLow() {
        return tauLow;
    }
}
