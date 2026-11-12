package com.sdip.ingest;

import com.sdip.domain.link.EntityLinker;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.service.CanonicalImportService;
import com.sdip.domain.service.MetricDictionary;
import com.sdip.domain.service.RowErrorRecorder;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Chunk-oriented transactional file-import job (§4.2):
 * ItemReader → ItemProcessor → ItemWriter with an explicit skip policy.
 * A chunk commits atomically or rolls back atomically; skipped rows are
 * quarantined row-by-row with full evidence.
 */
@Configuration
public class FileImportJobConfig {

    public static final String JOB_NAME = "fileImportJob";

    @Value("${sdip.ingestion.chunk-size:500}")
    private int chunkSize;

    @Value("${sdip.ingestion.skip-limit:100}")
    private int skipLimit;

    @Bean
    public Job fileImportJob(JobRepository jobRepository,
                             Step fileImportStep,
                             BatchBookkeepingListener bookkeepingListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(fileImportStep)
                .listener(bookkeepingListener)
                .build();
    }

    @Bean
    public Step fileImportStep(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               ItemReader<RowEnvelope> fileRowReader,
                               ItemProcessor<RowEnvelope, ImportChunk> mappingRowProcessor,
                               ItemWriter<ImportChunk> importChunkWriter,
                               RowErrorSkipListener rowErrorSkipListener) {
        return new StepBuilder("fileImportStep", jobRepository)
                .<RowEnvelope, ImportChunk>chunk(chunkSize, transactionManager)
                .reader(fileRowReader)
                .processor(mappingRowProcessor)
                .writer(importChunkWriter)
                .faultTolerant()
                .skipLimit(skipLimit)
                .skip(RowViolationException.class)
                .listener(rowErrorSkipListener)
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<RowEnvelope> fileRowReader(
            @Value("#{jobParameters['filePath']}") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalStateException("fileImportJob requires the 'filePath' job parameter");
        }
        Path path = Path.of(filePath);
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return new XlsxRowReader(path);
        }
        return new CsvRowReader(path);
    }

    @Bean
    @StepScope
    public ItemProcessor<RowEnvelope, ImportChunk> mappingRowProcessor(
            ColumnMappingService mappingService,
            MetricDictionary dictionary,
            EntityLinker linker,
            AthleteRepository athletes,
            RowErrorRecorder rowErrors,
            @Value("#{jobParameters['profileName']}") String profileName,
            @Value("#{jobParameters['squadId']}") String squadId,
            @Value("#{jobParameters['batchId']}") String batchId) {
        return new MappingRowProcessor(mappingService, dictionary, linker, athletes, rowErrors,
                profileName, UUID.fromString(squadId), UUID.fromString(batchId));
    }

    @Bean
    public ItemWriter<ImportChunk> importChunkWriter(CanonicalImportService importer) {
        return new ImportChunkWriter(importer);
    }

    @Bean
    @StepScope
    public RowErrorSkipListener rowErrorSkipListener(
            RowErrorRecorder recorder,
            @Value("#{jobParameters['batchId']}") String batchId) {
        return new RowErrorSkipListener(recorder, UUID.fromString(batchId));
    }
}
