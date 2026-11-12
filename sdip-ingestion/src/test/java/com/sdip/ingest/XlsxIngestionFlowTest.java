package com.sdip.ingest;

import com.sdip.TestIngestionApp;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.GpsMetricRepository;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.repo.SquadRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** XLSX ingestion across multiple sheets (§4.2), written AND read with streaming APIs. */
@SpringBootTest(classes = TestIngestionApp.class)
class XlsxIngestionFlowTest {

    @Autowired
    BatchJobLauncher launcher;
    @Autowired
    SquadRepository squads;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    GpsMetricRepository gps;
    @Autowired
    IngestionBatchRepository batches;

    @TempDir
    Path tempDir;

    @Test

    void multiSheetXlsx_ingestsEverySheet() throws Exception {
        Squad squad = squads.findByTenantCodeAndName("XLSX", "Xlsx Squad")
                .orElseGet(() -> squads.save(new Squad("Xlsx Squad", "XLSX")));
        Athlete sam = athletes.findByEmailIgnoreCase("sam.kerr@xlsx.test")
                .orElseGet(() -> athletes.save(new Athlete(squad, "Sam Kerr",
                        LocalDate.parse("1993-09-10"), "sam.kerr@xlsx.test", false)));

        Path xlsx = tempDir.resolve("catapult-history.xlsx");
        try (Workbook wb = new SXSSFWorkbook(null, 100, false, true);
             OutputStream out = Files.newOutputStream(xlsx)) {
            writeSheet(wb, "2025-07", "2025-07-0");
            writeSheet(wb, "2025-08", "2025-08-0");
            wb.write(out);
        }

        UUID batchId = launcher.launchSync(xlsx, "catapult-session-csv", squad.getId(), "tester", "ADMIN");

        assertThat(gps.countByAthleteId(sam.getId())).isEqualTo(6); // 3 rows in each of 2 sheets
        assertThat(batches.findById(batchId)).isPresent();
        assertThat(batches.findById(batchId).get().getRowsWritten()).isEqualTo(6);
    }

    private static void writeSheet(Workbook wb, String sheetName, String datePrefix) {
        Sheet sheet = wb.createSheet(sheetName);
        String[] headers = {"Player Name", "Email", "Player Id", "Date", "Session Id",
                "Total Distance", "HSR", "Player Load", "Max Vel (km/h)", "Duration"};
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }
        for (int r = 1; r <= 3; r++) {
            Row row = sheet.createRow(r);
            row.createCell(0).setCellValue("Sam Kerr");
            row.createCell(1).setCellValue("sam.kerr@xlsx.test");
            row.createCell(2).setCellValue("cat-sam-xlsx");
            row.createCell(3).setCellValue(datePrefix + r);
            row.createCell(4).setCellValue(sheetName + "-sess-" + r);
            row.createCell(5).setCellValue(6000 + r * 111);
            row.createCell(6).setCellValue(400 + r * 11);
            row.createCell(7).setCellValue(380 + r * 7);
            row.createCell(8).setCellValue(31.5);
            row.createCell(9).setCellValue(78);
        }
    }
}
