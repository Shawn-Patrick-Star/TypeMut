package org.fuzz.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FuzzCliOptions {
    public static final String FUZZ_CONFIG_PROPERTY = "fuzz.config";
    public static final String FILTER_CONFIG_PROPERTY = "seed.filter.config";
    public static final String DIFFTEST_CONFIG_PROPERTY = "difftest.config";

    private final boolean helpRequested;
    private final boolean reproduceRequested;

    private FuzzCliOptions(boolean helpRequested, boolean reproduceRequested) {
        this.helpRequested = helpRequested;
        this.reproduceRequested = reproduceRequested;
    }

    public static FuzzCliOptions parseAndApply(String[] args) {
        if (args == null || args.length == 0) {
            return new FuzzCliOptions(false, false);
        }

        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("--fuzzConfig", FUZZ_CONFIG_PROPERTY);
        mappings.put("--filterConfig", FILTER_CONFIG_PROPERTY);
        mappings.put("--difftestConfig", DIFFTEST_CONFIG_PROPERTY);

        boolean help = false;
        boolean reproduce = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
                continue;
            }
            if ("--reproduce".equals(arg) || "-R".equals(arg)) {
                reproduce = true;
                continue;
            }

            String property = mappings.get(arg);
            if (property == null) {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            String value = requireValue(args, ++i, arg);
            System.setProperty(property, value);
        }
        return new FuzzCliOptions(help, reproduce);
    }

    public boolean helpRequested() {
        return helpRequested;
    }

    public boolean reproduceRequested() {
        return reproduceRequested;
    }

    public static String usage(String command) {
        return """
                Usage:
                  %s [options]

                Options:
                  -R, --reproduce         Run BugReproducer instead of fuzzing.
                  --fuzzConfig <file>      Fuzz-app YAML config path.
                  --filterConfig <file>    Seed-filter YAML config path.
                  --difftestConfig <file>  Difftest-core YAML config path.
                  -h, --help               Show this help.
                """.formatted(command);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
