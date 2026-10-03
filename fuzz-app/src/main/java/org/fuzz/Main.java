package org.fuzz;

import org.fuzz.config.FuzzCliOptions;
import org.fuzz.config.FuzzConfig;
import org.fuzz.core.FuzzingEngine;
import org.fuzz.util.LoggingUtils;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        FuzzCliOptions options;
        try {
            options = FuzzCliOptions.parseAndApply(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(FuzzCliOptions.usage("java -jar fuzz-app-1.0-SNAPSHOT.jar"));
            System.exit(2);
            return;
        }
        if (options.helpRequested()) {
            System.out.println(FuzzCliOptions.usage("java -jar fuzz-app-1.0-SNAPSHOT.jar"));
            return;
        }
        configureLogging(options.reproduceRequested());

        if (options.reproduceRequested()) {
            try {
                BugReproducer.runFromCli();
            } catch (IOException e) {
                System.err.println("BugReproducer failed: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        FuzzingEngine engine = new FuzzingEngine();
        engine.run(FuzzConfig.SEED_DIR, FuzzConfig.OUTPUT_DIR);
    }

    private static void configureLogging(boolean reproduceMode) {
        LoggingUtils.configure(reproduceMode ? "reproducer" : "app", "INFO");
    }
}
