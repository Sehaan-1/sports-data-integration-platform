package com.sdip.ingest;

import java.util.Map;

/**
 * One raw input row flowing through the pipeline.
 *
 * @param rowNumber 1-based data-row number in the original file (header excluded)
 * @param cells     header-name → raw string value
 * @param raw       original row content for error reporting
 */
public record RowEnvelope(long rowNumber, Map<String, String> cells, String raw) {
}
