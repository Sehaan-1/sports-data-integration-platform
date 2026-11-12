package com.sdip.api;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR-01 module boundaries, made executable.
 *
 * - The domain module may not depend on any other module (ingest, connectors,
 *   security, api). It is the innermost layer.
 * - The ingestion module may not depend on the api or connectors modules.
 * - The security module may not depend on the ingest or connectors modules.
 *
 * Violations are real: either move the offending class, or amend ADR-01 in the
 * same commit. The rules are never weakened to force a green build.
 */
@AnalyzeClasses(packages = "com.sdip")
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule domainRule = noClasses()
            .that(JavaClass.Predicates.resideInAPackage("com.sdip.domain.."))
            .should(ArchConditions.dependOnClassesThat(
                    JavaClass.Predicates.resideInAnyPackage(
                            "com.sdip.ingest..",
                            "com.sdip.connectors..",
                            "com.sdip.security..",
                            "com.sdip.api..")));

    @ArchTest
    static final ArchRule ingestionRule = noClasses()
            .that(JavaClass.Predicates.resideInAPackage("com.sdip.ingest.."))
            .should(ArchConditions.dependOnClassesThat(
                    JavaClass.Predicates.resideInAnyPackage("com.sdip.api..", "com.sdip.connectors..")));

    @ArchTest
    static final ArchRule securityRule = noClasses()
            .that(JavaClass.Predicates.resideInAPackage("com.sdip.security.."))
            .should(ArchConditions.dependOnClassesThat(
                    JavaClass.Predicates.resideInAnyPackage("com.sdip.ingest..", "com.sdip.connectors..")));
}
