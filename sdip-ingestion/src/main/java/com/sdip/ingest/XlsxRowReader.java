package com.sdip.ingest;

import com.github.pjfanning.xlsx.StreamingReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heap-bounded XLSX reader (§4.2 / RQ1): event-driven SAX parsing via
 * excel-streaming-reader with a small shared-strings window and a row cache
 * of one, so a 100 MB workbook parses inside a 512 MB heap. Iterates all
 * sheets in order; the first non-empty row of each sheet is its header.
 */
public class XlsxRowReader implements ItemReader<RowEnvelope>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XlsxRowReader.class);

    private final Path file;
    private Workbook workbook;
    private InputStream input;
    private Iterator<Sheet> sheets;
    private Iterator<Row> currentRows;
    private List<String> headers;
    private long rowNumber = 0;
    private String currentSheet;
    private final DataFormatter formatter = new DataFormatter();

    public XlsxRowReader(Path file) {
        this.file = file;
    }

    private void openIfNeeded() {
        if (workbook != null) {
            return;
        }
        try {
            input = Files.newInputStream(file);
            workbook = StreamingReader.builder()
                    .rowCacheSize(1)              // minimal rows in memory
                    .bufferSize(8192)
                    .setReadSharedFormulas(false)
                    .open(input);
            sheets = workbook.sheetIterator();
            advanceSheet();
        } catch (Exception e) {
            throw new IllegalStateException("cannot open XLSX " + file + ": " + e.getMessage(), e);
        }
    }

    private boolean advanceSheet() {
        while (sheets.hasNext()) {
            Sheet sheet = sheets.next();
            currentRows = sheet.iterator();
            headers = null;
            currentSheet = sheet.getSheetName();
            log.info("XLSX: streaming sheet '{}'", currentSheet);
            return true;
        }
        return false;
    }

    @Override
    public RowEnvelope read() {
        openIfNeeded();
        while (true) {
            if (currentRows == null || !currentRows.hasNext()) {
                if (!advanceSheet()) {
                    return null;
                }
                continue;
            }
            Row row = currentRows.next();
            List<String> values = valuesOf(row);
            if (headers == null) {
                if (values.stream().allMatch(v -> v == null || v.isBlank())) {
                    continue; // leading blank rows before the header
                }
                headers = values.stream().map(v -> v == null ? "" : v.trim()).toList();
                continue;
            }
            if (values.stream().allMatch(v -> v == null || v.isBlank())) {
                continue; // blank line inside the sheet
            }
            rowNumber++;
            Map<String, String> cells = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).isEmpty()) {
                    continue;
                }
                cells.put(headers.get(i), i < values.size() ? values.get(i) : null);
            }
            return new RowEnvelope(rowNumber, cells, String.join(",", values));
        }
    }

    /** Header of the CURRENT sheet (open + advances to that sheet's header). */
    public List<String> headersOfFirstSheet() {
        openIfNeeded();
        while (headers == null && currentRows != null && currentRows.hasNext()) {
            List<String> values = valuesOf(currentRows.next());
            if (values.stream().anyMatch(v -> v != null && !v.isBlank())) {
                headers = values.stream().map(v -> v == null ? "" : v.trim()).toList();
            }
        }
        return headers != null ? headers : List.of();
    }

    private List<String> valuesOf(Row row) {
        List<String> values = new ArrayList<>();
        for (Cell cell : row) {
            int idx = cell.getColumnIndex();
            while (values.size() < idx) {
                values.add(null);
            }
            values.add(readCell(cell));
        }
        return values;
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                // render integral numerics without a trailing .0 (common for ids/years)
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        String v = formatter.formatCellValue(cell);
        return v != null ? v.trim() : null;
    }

    @Override
    public void close() throws IOException {
        if (workbook != null) {
            workbook.close();
        }
        if (input != null) {
            input.close();
        }
    }
}
