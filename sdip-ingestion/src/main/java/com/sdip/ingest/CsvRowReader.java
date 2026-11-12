package com.sdip.ingest;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.springframework.batch.item.ItemReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming CSV reader (OpenCSV). Constant memory: one row at a time.
 * The first line is treated as the header; blank lines are skipped.
 */
public class CsvRowReader implements ItemReader<RowEnvelope>, AutoCloseable {

    private final Path file;
    private CSVReader reader;
    private String[] headers;
    private long rowNumber = 0;

    public CsvRowReader(Path file) {
        this.file = file;
    }

    private void openIfNeeded() {
        if (reader != null) {
            return;
        }
        try {
            Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            reader = new CSVReaderBuilder(new BufferedReader(in))
                    .withCSVParser(new CSVParserBuilder().build())
                    .build();
            headers = reader.readNext();
            if (headers == null) {
                throw new RowViolationException("empty file: " + file);
            }
            // strip UTF-8 BOM if present
            if (headers.length > 0 && headers[0] != null) {
                headers[0] = headers[0].replace("﻿", "");
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot open CSV file " + file + ": " + e.getMessage(), e);
        }
    }

    public String[] headers() {
        openIfNeeded();
        return headers;
    }

    @Override
    public RowEnvelope read() {
        openIfNeeded();
        try {
            String[] line = reader.readNext();
            if (line == null) {
                return null;
            }
            rowNumber++;
            if (isBlank(line)) {
                return read(); // skip blank lines
            }
            Map<String, String> cells = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) {
                cells.put(headers[i].trim(), i < line.length ? line[i] : null);
            }
            return new RowEnvelope(rowNumber, cells, String.join(",", line));
        } catch (RowViolationException e) {
            throw e;
        } catch (Exception e) {
            rowNumber++;
            throw new RowViolationException("CSV parse failure at row " + rowNumber + ": " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String[] line) {
        for (String s : line) {
            if (s != null && !s.isBlank()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
