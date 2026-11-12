package com.sdip.connectors;

import java.time.Instant;

/** Outcome of one connector sync run (§4.1). */
public record SyncReport(String vendor,
                         Status status,
                         Instant windowFrom,
                         Instant windowTo,
                         int recordsRead,
                         int recordsWritten,
                         int recordsQuarantined,
                         String error) {

    public enum Status {OK, DEGRADED, DISABLED}

    public static SyncReport ok(String vendor, Instant from, Instant to, int read, int written, int quarantined) {
        return new SyncReport(vendor, Status.OK, from, to, read, written, quarantined, null);
    }

    public static SyncReport degraded(String vendor, Instant from, Instant to, String error) {
        return new SyncReport(vendor, Status.DEGRADED, from, to, 0, 0, 0, error);
    }

    public static SyncReport disabled(String vendor) {
        return new SyncReport(vendor, Status.DISABLED, null, null, 0, 0, 0, "connector disabled");
    }
}
