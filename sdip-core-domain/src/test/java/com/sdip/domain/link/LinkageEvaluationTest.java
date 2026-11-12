package com.sdip.domain.link;

import com.sdip.TestCoreApp;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.SquadRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RQ2 — entity-resolution evaluation on a generated 1,000-athlete labeled
 * synthetic corpus (§8). Perturbation models follow record-linkage practice
 * (nicknames, typos, transliteration, DOB off-by-one transcription errors)
 * plus adversarial decoys: distinct real athletes that share a master's name.
 *
 * Success criteria asserted: auto-link precision ≥ 0.98, recall ≥ 0.90.
 */
@SpringBootTest(classes = TestCoreApp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LinkageEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(LinkageEvaluationTest.class);

    @Autowired
    EntityLinker linker;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    SquadRepository squads;
    @Autowired
    PlatformTransactionManager txm;

    private static final String[] FIRST = {
            "Alexander", "Benjamin", "Christopher", "Daniel", "Elias", "Felix", "Gabriel", "Henry",
            "Ivan", "Jonas", "Kylian", "Lucas", "Mateo", "Nathan", "Oliver", "Patrick", "Rafael",
            "Sebastian", "Thomas", "Victor", "William", "Yusuf", "Zinedine", "Andre", "Bruno",
            "Carlos", "Diego", "Emre", "Fabio", "Giovanni", "Hugo", "Idrissa", "Jamal", "Karim",
            "Luka", "Marcus", "Niklas", "Oscar", "Pedro", "Rodrigo", "Son", "Takefusa"
    };
    private static final String[] LAST = {
            "Silva", "Muller", "Rossi", "Smith", "Tanaka", "Kim", "Garcia", "Novak", "Haddad",
            "Okafor", "Santos", "Weber", "Fischer", "Santos", "Fernandez", "Larsen", "Berg",
            "Costa", "Ricci", "Moreau", "Petit", "Kane", "Walker", "Hughes", "Mensah", "Osei",
            "Diallo", "Traore", "Nakamura", "Singh", "Patel", "Iqbal", "Hernandez", "Lopez",
            "Wright", "Sule", "Krause", "Weiss", "Brandt", "Lindgren"
    };
    private static final Map<String, String> NICKNAMES = Map.ofEntries(
            Map.entry("Alexander", "Alex"), Map.entry("Benjamin", "Ben"),
            Map.entry("Christopher", "Chris"), Map.entry("Daniel", "Danny"),
            Map.entry("Nathan", "Nate"), Map.entry("Patrick", "Pat"),
            Map.entry("Thomas", "Tom"), Map.entry("William", "Will"),
            Map.entry("Rodrigo", "Rodri"), Map.entry("Giovanni", "Gio"),
            Map.entry("Karim", "Karo"), Map.entry("Marcus", "Marc"));

    private record Master(UUID id, String fullName, LocalDate dob, String email) {
    }

    /** truthAthleteId == null means: this row belongs to NO master (decoy → must not auto-link). */
    private record VendorRow(String sourceSystem, String sourceAthleteId, String name,
                             LocalDate dob, String email, UUID truthAthleteId) {
    }

    @Test
    @Order(1)
    void rq2_precisionRecallOnLabeledCorpus() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        Random rng = new Random(20240815L);

        Squad squad = tx.execute(s -> squads.save(new Squad("Eval Squad", "EVAL")));

        // ---- 1) Masters: 994 (full names unique unless a natural collision occurs,
        // which is kept deliberately) + 6 exact name+DOB twins ----
        List<Master> masters = new ArrayList<>();
        Map<String, Integer> nameUse = new java.util.HashMap<>();
        for (int i = 0; i < 994; i++) {
            String first = FIRST[rng.nextInt(FIRST.length)];
            String last = LAST[rng.nextInt(LAST.length)];
            String name = first + " " + last;
            int uses = nameUse.merge(name, 1, Integer::sum);
            if (uses > 1) {
                name = name + " " + uses; // disambiguating suffix only on true collisions
            }
            LocalDate dob = LocalDate.of(1995 + rng.nextInt(13), 1 + rng.nextInt(12), 1 + rng.nextInt(28));
            String email = (first + "." + last.replace(" ", "") + i + "@club.test").toLowerCase();
            masters.add(new Master(null, name, dob, email));
        }
        // adversarial exact twins: same name, same dob, different people (ground truth distinct)
        for (int t = 0; t < 6; t++) {
            Master base = masters.get(t * 7);
            masters.add(new Master(null, base.fullName(), base.dob(),
                    ("twin." + t + "." + base.email())));
        }

        List<UUID> masterIds = tx.execute(s -> {
            List<UUID> ids = new ArrayList<>();
            for (Master m : masters) {
                Athlete saved = athletes.save(new Athlete(squad, m.fullName(), m.dob(), m.email(), false));
                ids.add(saved.getId());
            }
            return ids;
        });
        List<Master> mastersWithIds = new ArrayList<>();
        for (int i = 0; i < masters.size(); i++) {
            Master m = masters.get(i);
            mastersWithIds.add(new Master(masterIds.get(i), m.fullName(), m.dob(), m.email()));
        }

        // ---- 2) Vendor rows with controlled perturbations (ground-truth labels kept) ----
        List<VendorRow> rows = new ArrayList<>();
        int rowSeq = 0;
        for (Master m : mastersWithIds) {
            // every master gets a CATAPULT row; 60% also a VALD row
            rows.add(perturbed(rng, m, "CATAPULT", "cat-" + m.id().toString().substring(0, 8)));
            if (rng.nextDouble() < 0.60) {
                rows.add(perturbed(rng, m, "VALD", "vald-p-" + (rowSeq++)));
            }
        }
        // 40 decoys: name of a random master, different person (random dob), no email,
        // ground truth = NEW athlete. Auto-linking these to the master is a false merge.
        // Decoys avoid colliding on EVERY identity field with an existing master
        // (name+exact dob+— : such a row would be unanswerable even for a human).
        Map<String, List<LocalDate>> dobsByName = new java.util.HashMap<>();
        for (Master m : mastersWithIds) {
            dobsByName.computeIfAbsent(m.fullName(), k -> new ArrayList<>()).add(m.dob());
        }
        for (int d = 0; d < 40; d++) {
            Master like = mastersWithIds.get(100 + rng.nextInt(800));
            List<LocalDate> forbidden = dobsByName.getOrDefault(like.fullName(), List.of());
            LocalDate foreignDob = null;
            for (int attempt = 0; attempt < 10 && foreignDob == null; attempt++) {
                LocalDate candidate = like.dob().plusYears(rng.nextInt(5) - 2).plusDays(rng.nextInt(20) - 10);
                if (!candidate.equals(like.dob()) && !forbidden.contains(candidate)) {
                    foreignDob = candidate;
                }
            }
            if (foreignDob == null) {
                continue;
            }
            rows.add(new VendorRow("VALD", "decoy-" + d, like.fullName(), foreignDob, null, null));
        }

        // ---- 3) Resolution sweep ----
        int autoTotal = 0, autoCorrect = 0, quarantined = 0, decoysLeaked = 0;
        int total = rows.size();
        for (VendorRow row : rows) {
            final VendorRow r = row;
            LinkOutcome out = tx.execute(s -> linker.resolve(new IdentityRecord(
                    r.sourceSystem(), r.sourceAthleteId(), r.name(), r.dob(), r.email(), squad.getId())));
            switch (out.kind()) {
                case LINKED -> {
                    autoTotal++;
                    if (r.truthAthleteId() != null && out.athleteId().equals(r.truthAthleteId())) {
                        autoCorrect++;
                    } else if (r.truthAthleteId() == null) {
                        decoysLeaked++;
                        final LinkOutcome o2 = out;
                        tx.executeWithoutResult(s -> {
                            Athlete wrong = athletes.findById(o2.athleteId()).orElseThrow();
                            log.warn("DECOY LEAK: '{}' dob={} auto-linked to master '{}' dob={}",
                                    r.name(), r.dob(), wrong.getFullName(), wrong.getDob());
                        });
                    }
                    // linked-to-wrong-master simply fails to increment autoCorrect
                }
                case PROVISIONAL -> {
                    autoTotal++;
                    if (r.truthAthleteId() == null) {
                        autoCorrect++; // correctly identified as a brand-new athlete
                    }
                }
                case QUARANTINED -> quarantined++;
            }
        }

        double precision = autoTotal == 0 ? 0 : (double) autoCorrect / autoTotal;
        double recall = (double) autoCorrect / total;
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double quarantineRate = (double) quarantined / total;

        log.info("RQ2 results: rows={} auto={} correct={} quarantined={} decoysLeaked={}",
                total, autoTotal, autoCorrect, quarantined, decoysLeaked);
        log.info("RQ2 metrics: precision={} recall={} F1={} quarantineRate={}",
                fmt(precision), fmt(recall), fmt(f1), fmt(quarantineRate));

        assertThat(precision).as("auto-link precision ≥ 0.98").isGreaterThanOrEqualTo(0.98);
        assertThat(recall).as("auto-link recall ≥ 0.90").isGreaterThanOrEqualTo(0.90);
        assertThat(decoysLeaked).as("decoy false merges").isEqualTo(0);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private VendorRow perturbed(Random rng, Master m, String system, String externalId) {
        String[] parts = m.fullName().split(" ");
        String first = parts[0];
        String last = parts[1];
        double roll = rng.nextDouble();
        if (roll < 0.15 && NICKNAMES.containsKey(first)) {
            first = NICKNAMES.get(first);                       // nickname
        } else if (roll < 0.25) {
            last = typo(rng, last);                             // one-char surname typo
        } else if (roll < 0.30) {
            last = last.replace("Muller", "Mueller")            // transliteration
                    .replace("Sule", "Suele").replace("Lopez", "Lopes");
        }
        LocalDate dob = m.dob();
        if (rng.nextDouble() < 0.08) {
            dob = dob.plusDays(1);                              // classic DOB off-by-one
        }
        String email = rng.nextDouble() < 0.72 ? m.email() : null; // 72% of rows carry verified email
        return new VendorRow(system, externalId, first + " " + last, dob, email, m.id());
    }

    private static String typo(Random rng, String s) {
        if (s.length() < 3) {
            return s;
        }
        int pos = 1 + rng.nextInt(s.length() - 1);
        char repl = (char) ('a' + rng.nextInt(26));
        return s.substring(0, pos) + repl + s.substring(Math.min(pos + 1, s.length()));
    }
}
