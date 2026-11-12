package com.sdip.ingest;

import com.sdip.domain.service.CanonicalImportService;
import com.sdip.domain.service.ImportRecords.ForceImport;
import com.sdip.domain.service.ImportRecords.GpsImport;
import com.sdip.domain.service.ImportRecords.WellnessImport;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ItemWriter: flattens per-row {@link ImportChunk}s, de-duplicates within the
 * chunk on the natural key (last occurrence wins — chronologically latest
 * vendor edit), then performs one bulk idempotent upsert per fact family.
 */
public class ImportChunkWriter implements ItemWriter<ImportChunk> {

    private final CanonicalImportService importer;

    public ImportChunkWriter(CanonicalImportService importer) {
        this.importer = importer;
    }

    @Override
    public void write(Chunk<? extends ImportChunk> chunk) {
        Map<String, GpsImport> gps = new LinkedHashMap<>();
        Map<String, ForceImport> force = new LinkedHashMap<>();
        Map<String, WellnessImport> wellness = new LinkedHashMap<>();
        for (ImportChunk item : chunk.getItems()) {
            item.gps().forEach(g -> gps.put(nk(g.sourceSystem(), g.sourceRecordId()), g));
            item.force().forEach(f -> force.put(nk(f.sourceSystem(), f.sourceRecordId()), f));
            item.wellness().forEach(w -> wellness.put(nk(w.sourceSystem(), w.sourceRecordId()), w));
        }
        if (!gps.isEmpty()) {
            importer.upsertGps(new ArrayList<>(gps.values()));
        }
        if (!force.isEmpty()) {
            importer.upsertForce(new ArrayList<>(force.values()));
        }
        if (!wellness.isEmpty()) {
            importer.upsertWellness(new ArrayList<>(wellness.values()));
        }
    }

    private static String nk(String sourceSystem, String sourceRecordId) {
        return sourceSystem + "|" + sourceRecordId;
    }

    /** Test/diagnostic hook. */
    static List<GpsImport> dedupeGps(List<GpsImport> rows) {
        Map<String, GpsImport> map = new LinkedHashMap<>();
        rows.forEach(g -> map.put(nk(g.sourceSystem(), g.sourceRecordId()), g));
        return new ArrayList<>(map.values());
    }
}
