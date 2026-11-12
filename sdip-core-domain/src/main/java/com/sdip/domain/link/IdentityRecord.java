package com.sdip.domain.link;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Observed identity payload carried by an incoming record (any source).
 *
 * @param sourceSystem    vendor / origin key, e.g. {@code CATAPULT}, {@code VALD}, {@code CSV_LEGACY}
 * @param sourceAthleteId vendor identifier for the athlete (nullable when absent from the source)
 * @param fullName        observed name (nullable)
 * @param dob             observed date of birth (nullable)
 * @param email           observed institutional email (nullable)
 * @param defaultSquadId  squad used when a provisional athlete must be created
 */
public record IdentityRecord(String sourceSystem,
                             String sourceAthleteId,
                             String fullName,
                             LocalDate dob,
                             String email,
                             UUID defaultSquadId) {
}
