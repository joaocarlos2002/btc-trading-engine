package dev.romeo.btctradingengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The single entry point (issue #77): starts the dashboard and the live pipeline in one Spring context.
 * The settings are the typed {@code *Properties} records in the config package (issue #101), validated
 * while the context starts; LivePipelineConfiguration wires the pipeline and LivePipeline starts it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("dev.romeo.btctradingengine.config")
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            SpringApplication.run(Main.class, args);
        } catch (Exception e) {
            logger.error("Application failed", e);
            System.exit(1);
        }
    }
}
