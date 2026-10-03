package org.seed.cli;

import org.seed.config.SeedFilterConfig;
import org.seed.model.SeedFilterResult;
import org.seed.service.SeedFilterService;
import org.seed.util.LoggingUtils;

import java.nio.file.Path;

public class SeedFilterCli {

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options.help()) {
            printUsage();
            return;
        }
        applyDifftestConfig(options);

        SeedFilterConfig config = options.filterConfig() == null
                ? SeedFilterConfig.defaults()
                : SeedFilterConfig.fromProperties(options.filterConfig());
        if (options.output() != null) {
            config = config.withOutputDir(options.output());
        }
        LoggingUtils.configureForCli(config);

        Path input = options.input() != null ? options.input() : config.inputDir();
        Path output = config.outputDir();
        if (input == null || output == null) {
            printUsage();
            System.exit(2);
        }

        SeedFilterResult result = new SeedFilterService().filter(input, output, config);
        System.out.printf("Seed filter finished: accepted=%d rejected=%d static=%d compileFailed=%d rawDiff=%d%n",
                result.acceptedCount(),
                result.rejectedCount(),
                result.staticFilteredCount(),
                result.compileFailedCount(),
                result.rawDiffFailedCount());
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -jar seed-filter-1.0-SNAPSHOT.jar --input <seedRoot> [options]

                Options:
                  --input <dir>             Seed corpus root directory. Defaults to SeedFilter.input_dir.
                  --output <dir>            Seed filter output directory. Defaults to SeedFilter.output_dir.
                  --filterConfig <file>     Optional seed-filter YAML or legacy properties file.
                  --difftestConfig <file>   Optional difftest YAML file.
                  --config <file>           Alias for --filterConfig.
                  --help                    Print this help.
                """);
    }

    private static void applyDifftestConfig(CliOptions options) {
        if (options.difftestConfig() != null) {
            System.setProperty("difftest.config", options.difftestConfig().toAbsolutePath().normalize().toString());
        }
    }

    private record CliOptions(Path input, Path output, Path filterConfig, Path difftestConfig, boolean help) {
        private static CliOptions parse(String[] args) {
            Path input = null;
            Path output = null;
            Path filterConfig = null;
            Path difftestConfig = null;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> input = Path.of(next(args, ++i, "--input"));
                    case "--output" -> output = Path.of(next(args, ++i, "--output"));
                    case "--config", "--filterConfig" -> {
                        String option = args[i];
                        filterConfig = Path.of(next(args, ++i, option));
                    }
                    case "--difftestConfig" -> difftestConfig = Path.of(next(args, ++i, "--difftestConfig"));
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new CliOptions(input, output, filterConfig, difftestConfig, help);
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}

