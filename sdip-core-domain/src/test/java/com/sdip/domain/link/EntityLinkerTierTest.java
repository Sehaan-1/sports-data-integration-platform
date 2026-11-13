package com.sdip.domain.link;

import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.AthleteSourceLinkRepository;
import com.sdip.domain.repo.LinkQuarantineRepository;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.TestCoreApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast deterministic checks of the tiered pipeline (§4.3). */
@SpringBootTest(classes = TestCoreApp.class)
@Transactional
class EntityLinkerTierTest {

    @Autowired
    EntityLinker linker;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    AthleteSourceLinkRepository links;
    @Autowired
    LinkQuarantineRepository quarantine;
    @Autowired
    SquadRepository squads;

    private Squad squad;

    @BeforeEach
    void setup() {
        squad = squads.save(new Squad("Tier Test Squad", "TIER-TEST"));
    }

    private Athlete master(String name, String dob, String email) {
        return athletes.save(new Athlete(squad, name,
                LocalDate.parse(dob), email, false));
    }

    @Test
    void tier1_deterministicEmail_autoLinks() {
        Athlete m = master("Sam Kerr", "1993-09-10", "sam.kerr@club.test");
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "VALD", "p-001", "S. Kerr", null, "sam.kerr@club.test", squad.getId()));
        assertThat(out.kind()).isEqualTo(LinkOutcome.Kind.LINKED);
        assertThat(out.athleteId()).isEqualTo(m.getId());
        assertThat(links.findBySourceSystemAndSourceAthleteIdAndStatus(
                "VALD", "p-001", com.sdip.domain.entity.AthleteSourceLink.LinkStatus.ACTIVE))
                .isPresent();
    }

    @Test
    void tier1_vendorReidentification_isIdempotent() {
        Athlete m = master("Ada Hegerberg", "1995-07-10", "ada@club.test");
        IdentityRecord id = new IdentityRecord("CATAPULT", "cat-55", "Ada Hegerberg",
                LocalDate.parse("1995-07-10"), null, squad.getId());
        LinkOutcome first = linker.resolve(id);
        LinkOutcome second = linker.resolve(id);
        assertThat(first.kind()).isEqualTo(LinkOutcome.Kind.LINKED);
        assertThat(second.kind()).isEqualTo(LinkOutcome.Kind.LINKED);
        assertThat(second.athleteId()).isEqualTo(m.getId());
        // exactly ONE link row exists for this vendor identity
        assertThat(links.findBySourceSystemAndSourceAthleteId("CATAPULT", "cat-55")).hasSize(1);
    }

    @Test
    void tier2_closeMatchAboveTauHigh_autoLinks() {
        // same person, last-name typo: Jaro–Winkler high, phonetics identical, DOB exact
        Athlete m = master("Vivianne Miedema", "1996-07-15", null);
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "CSV_LEGACY", null, "Vivianne Miedma", LocalDate.parse("1996-07-15"), null, squad.getId()));
        assertThat(out.kind()).isEqualTo(LinkOutcome.Kind.LINKED);
        assertThat(out.athleteId()).isEqualTo(m.getId());
    }

    @Test
    void tier2_midBandScore_goesToQuarantine_notAutoLink() {
        // same name, DOB off-by-one → score ≈ 0.847 → quarantine band [0.68, 0.90)
        Athlete m = master("Jordan Meyer", "1998-03-04", null);
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "VALD", "p-777", "Jordan Meyer", LocalDate.parse("1998-03-05"), null, squad.getId()));
        assertThat(out.kind()).isEqualTo(LinkOutcome.Kind.QUARANTINED);
        assertThat(out.quarantineId()).isNotNull();
        assertThat(quarantine.findById(out.quarantineId())).isPresent();

        // idempotent quarantine: re-presenting the same identity does not duplicate the queue entry
        LinkOutcome again = linker.resolve(new IdentityRecord(
                "VALD", "p-777", "Jordan Meyer", LocalDate.parse("1998-03-05"), null, squad.getId()));
        assertThat(again.kind()).isEqualTo(LinkOutcome.Kind.QUARANTINED);
        assertThat(quarantine.findBySourceSystemAndSourceAthleteId("VALD", "p-777")).hasSize(1);
        assertThat(m.getId()).isNotNull(); // sanity
    }

    @Test
    void tier2_ambiguousTwins_neverAutoLink() {
        // two distinct athletes, identical name and dob → tie at 1.0 must quarantine (R3)
        master("Chris Doe", "2000-01-01", null);
        master("Chris Doe", "2000-01-01", null);
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "CATAPULT", "cat-900", "Chris Doe", LocalDate.parse("2000-01-01"), null, squad.getId()));
        assertThat(out.kind()).isEqualTo(LinkOutcome.Kind.QUARANTINED);
        assertThat(links.findBySourceSystemAndSourceAthleteId("CATAPULT", "cat-900")).isEmpty(); // no automatic link was created
    }

    @Test
    void tier2_unfamiliarIdentity_createsProvisionalAndLinksDeterministically() {
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "CATAPULT", "cat-new-1", "Zzz Unkn Own", LocalDate.parse("1985-12-25"), null, squad.getId()));
        assertThat(out.kind()).isEqualTo(LinkOutcome.Kind.PROVISIONAL);
        Athlete created = athletes.findById(out.athleteId()).orElseThrow();
        assertThat(created.isProvisional()).isTrue();

        // stable on re-ingestion
        LinkOutcome again = linker.resolve(new IdentityRecord(
                "CATAPULT", "cat-new-1", "Zzz Unkn Own", LocalDate.parse("1985-12-25"), null, squad.getId()));
        assertThat(again.linkable()).isTrue();
        assertThat(again.athleteId()).isEqualTo(created.getId());
    }

    @Test
    void tenantIsolation_neverMatchesAcrossSquads() {
        Squad other = squads.save(new Squad("Other Squad", "TIER-TEST"));
        Athlete foreign = athletes.save(new Athlete(other, "Tenant Secret",
                LocalDate.parse("1990-01-01"), "secret@other.test", false));
        LinkOutcome out = linker.resolve(new IdentityRecord(
                "VALD", "p-x", "Tenant Secret", LocalDate.parse("1990-01-01"),
                "secret@other.test", squad.getId())); // OUR squad context
        assertThat(out.kind()).isNotEqualTo(LinkOutcome.Kind.LINKED);
        assertThat(out.athleteId()).isNotEqualTo(foreign.getId());
    }

}
