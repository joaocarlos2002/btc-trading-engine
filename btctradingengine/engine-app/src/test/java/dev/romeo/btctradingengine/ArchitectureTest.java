package dev.romeo.btctradingengine;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Layer rules over the whole reactor (issue #110). engine-app's test classpath holds every module,
 * so the rules also catch dependencies the Maven module graph would allow (e.g. between two
 * packages of engine-core). Plain JUnit tests calling {@code check}, so no extra test engine is
 * needed.
 */
class ArchitectureTest {

    /** The pure domain: indicators, features and the rule-based prediction. */
    private static final String[] DOMAIN = {
        "dev.romeo.btctradingengine.indicator..",
        "dev.romeo.btctradingengine.feature..",
        "dev.romeo.btctradingengine.prediction.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("dev.romeo.btctradingengine");
    }

    @Test
    void domainDoesNotDependOnConfigOrSpring() {
        noClasses()
                .that()
                .resideInAnyPackage(DOMAIN)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.romeo.btctradingengine.config..", "org.springframework..", "jakarta..")
                .because(
                        "indicators, features and prediction are plain Java, reused as-is by the backtest")
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnInfrastructure() {
        noClasses()
                .that()
                .resideInAnyPackage(DOMAIN)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.romeo.btctradingengine.trading..",
                        "dev.romeo.btctradingengine.adapter..",
                        "dev.romeo.btctradingengine.persistence..",
                        "dev.romeo.btctradingengine.dashboard..",
                        "dev.romeo.btctradingengine.backtest..",
                        "java.sql..",
                        "java.net.http..")
                .because("the domain reaches the outside world only through the port package")
                .check(classes);
    }

    @Test
    void backtestDoesNotDependOnTrading() {
        noClasses()
                .that()
                .resideInAPackage("dev.romeo.btctradingengine.backtest..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("dev.romeo.btctradingengine.trading..")
                .because(
                        "a backtest simulates its own positions and must never reach the order execution path")
                .check(classes);
    }

    /**
     * Three cycles existed when the rule was added (2026-09-17) and are ignored here, one direction
     * each, so no new cycle can appear while they are paid off:
     *
     * <ul>
     *   <li>dashboard -> config: DashboardState, DashboardWebSocketConfig and BacktestServiceConfig
     *       take the *Properties records, while config wires the dashboard beans;
     *   <li>persistence -> orderbook: OrderBookSnapshotWriter takes
     *       BinanceDepthClient.DepthSnapshot, while OrderBookPoller writes through the persistence
     *       writer;
     *   <li>metrics -> config: MetricsConfiguration reads the *Properties records, while
     *       LivePipelineConfiguration takes PipelineMetrics.
     * </ul>
     *
     * Remove an ignore once its cycle is broken (e.g. move the snapshot record to model).
     */
    @Test
    void noCyclesBetweenTopLevelPackages() {
        slices().matching("dev.romeo.btctradingengine.(*)..")
                .should()
                .beFreeOfCycles()
                .ignoreDependency(
                        resideInAPackage("dev.romeo.btctradingengine.dashboard.."),
                        resideInAPackage("dev.romeo.btctradingengine.config.."))
                .ignoreDependency(
                        resideInAPackage("dev.romeo.btctradingengine.persistence.."),
                        resideInAPackage("dev.romeo.btctradingengine.orderbook.."))
                .ignoreDependency(
                        resideInAPackage("dev.romeo.btctradingengine.metrics.."),
                        resideInAPackage("dev.romeo.btctradingengine.config.."))
                .check(classes);
    }
}
