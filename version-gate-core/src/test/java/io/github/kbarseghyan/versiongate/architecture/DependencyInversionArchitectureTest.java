package io.github.kbarseghyan.versiongate.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.kbarseghyan.versiongate",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DependencyInversionArchitectureTest {

  private static final String BASE_PACKAGE = "io.github.kbarseghyan.versiongate";

  @ArchTest
  static final ArchRule SPI_POLICY_AND_CONTRACTS_MUST_NOT_DEPEND_ON_OUTER_LAYERS =
      noClasses()
          .that()
          .resideInAnyPackage(
              BASE_PACKAGE + ".api..", BASE_PACKAGE + ".domain..", BASE_PACKAGE + ".port..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              BASE_PACKAGE,
              BASE_PACKAGE + ".application..",
              BASE_PACKAGE + ".adapter..",
              BASE_PACKAGE + ".configuration..",
              BASE_PACKAGE + ".control.postgres..",
              BASE_PACKAGE + ".snapshot.s3..",
              BASE_PACKAGE + ".testkit..",
              "org.springframework..",
              "jakarta.servlet..",
              "io.swagger.v3..",
              "java.sql..",
              "javax.sql..",
              "org.postgresql..",
              "software.amazon.awssdk..")
          .because(
              "the SPI owns framework-free domain policy, stable contracts, and ports, so dependencies must point inward toward it");

  @ArchTest
  static final ArchRule APPLICATION_USE_CASES_MUST_NOT_DEPEND_ON_OUTER_ADAPTERS =
      noClasses()
          .that()
          .resideInAPackage(BASE_PACKAGE + ".application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              BASE_PACKAGE,
              BASE_PACKAGE + ".adapter..",
              BASE_PACKAGE + ".configuration..",
              BASE_PACKAGE + ".control.postgres..",
              BASE_PACKAGE + ".snapshot.s3..",
              BASE_PACKAGE + ".testkit..",
              "org.springframework..",
              "jakarta.servlet..",
              "io.swagger.v3..",
              "java.sql..",
              "javax.sql..",
              "org.postgresql..",
              "software.amazon.awssdk..")
          .because(
              "application use cases may depend on SPI domain contracts and ports, but not on concrete adapters, delivery, bootstrap, or infrastructure frameworks");
}
