package com.sdip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sports Data Integration Platform — modular monolith (ADR-01).
 * One deployable; strictly bounded Maven modules discovered via
 * component scan from this root package.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class SdipApplication {

    public static void main(String[] args) {
        SpringApplication.run(SdipApplication.class, args);
    }
}
