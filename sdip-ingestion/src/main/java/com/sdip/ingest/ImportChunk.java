package com.sdip.ingest;

import com.sdip.domain.service.ImportRecords.ForceImport;
import com.sdip.domain.service.ImportRecords.GpsImport;
import com.sdip.domain.service.ImportRecords.WellnessImport;

import java.util.List;

/** Output of the item processor — canonical records grouped by fact family. */
public record ImportChunk(List<GpsImport> gps,
                          List<ForceImport> force,
                          List<WellnessImport> wellness) {

    public static ImportChunk gpsOnly(GpsImport g) {
        return new ImportChunk(List.of(g), List.of(), List.of());
    }

    public static ImportChunk forceOnly(ForceImport f) {
        return new ImportChunk(List.of(), List.of(f), List.of());
    }

    public static ImportChunk wellnessOnly(WellnessImport w) {
        return new ImportChunk(List.of(), List.of(), List.of(w));
    }
}
