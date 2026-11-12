package com.sdip.domain.link;

import java.util.UUID;

/**
 * Result of routing one incoming identity through the tiered pipeline (§4.3).
 */
public record LinkOutcome(Kind kind, UUID athleteId, UUID quarantineId, double score) {

    public enum Kind {
        /** Matched (or re-confirmed) to a master athlete; fact rows may be written. */
        LINKED,
        /** Ambiguous — routed to the human review queue; fact rows are held. */
        QUARANTINED,
        /** No plausible candidate; a provisional master athlete was created. */
        PROVISIONAL
    }

    public static LinkOutcome linked(UUID athleteId, double score) {
        return new LinkOutcome(Kind.LINKED, athleteId, null, score);
    }

    public static LinkOutcome quarantined(UUID quarantineId, double score) {
        return new LinkOutcome(Kind.QUARANTINED, null, quarantineId, score);
    }

    public static LinkOutcome provisional(UUID athleteId) {
        return new LinkOutcome(Kind.PROVISIONAL, athleteId, null, 0.0);
    }

    public boolean linkable() {
        return kind == Kind.LINKED || kind == Kind.PROVISIONAL;
    }
}
