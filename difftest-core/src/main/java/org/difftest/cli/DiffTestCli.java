package org.difftest.cli;

import org.difftest.DTPipeline;
import org.difftest.config.DTConfig;
import org.difftest.model.DTResult;
import org.difftest.model.DTType;
import org.difftest.util.LoggingUtils;
import org.difftest.util.MainClassResolver;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public final class DiffTestCli {
    private static final Path DEMO_SOURCE = Path.of("difftest-core", "demo", "Test.java");

    private DiffTestCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        CliOptions options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return 64;
        }

        if (options.help) {
            printUsage();
            return 0;
        }

        try {
            applyConfigOverrides(options);
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            return 64;
        }

        LoggingUtils.configureForCli();

        Path sourceFile = resolveSourceFile(options);
        if (!Files.isRegularFile(sourceFile)) {
            log.error("Source file does not exist: " + sourceFile);
            return 66;
        }
        log.info("[DTConfig] Source file path: " + sourceFile);

        String mainClassName = MainClassResolver.resolve(sourceFile, options.mainClassName);
        DTConfig.logResourceSummary();

        try (DTPipeline pipeline = new DTPipeline()) {
            DTResult result = pipeline.runPipeline(sourceFile, mainClassName);
            log.info(result.toString());
            return toExitCode(result.getType());
        } catch (RuntimeException e) {
            log.error("difftest failed: " + e.getMessage());
            return 70;
        }
    }

    private static CliOptions parse(String[] args) {
        CliOptions options = new CliOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> options.help = true;
                case "-s", "--source", "--file" -> options.sourceFile = Path.of(requireValue(args, ++i, arg));
                case "-m", "--main", "--main-class" -> options.mainClassName = requireValue(args, ++i, arg);
                case "-c", "--config" -> options.putOverride("difftest.config", requireValue(args, ++i, arg));
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
            }
        }

        return options;
    }

    private static void applyConfigOverrides(CliOptions options) {
        for (Map.Entry<String, String> entry : options.overrides.entrySet()) {
            String key = entry.getKey();
            if (!isAllowedOverride(key)) {
                throw new IllegalArgumentException("Unsupported config property: " + key);
            }
            if ("difftest.config".equals(key)) {
                Path configPath = Path.of(entry.getValue()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(configPath)) {
                    throw new IllegalArgumentException("Config file does not exist: " + configPath);
                }
                System.setProperty(key, configPath.toString());
            } else {
                System.setProperty(key, entry.getValue());
            }
        }
    }

    private static boolean isAllowedOverride(String key) {
        return key.equals("difftest.config");
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("Missing value for option: " + option);
        }
        return args[index];
    }

    private static Path resolveSourceFile(CliOptions options) {
        if (options.sourceFile != null) {
            return options.sourceFile.toAbsolutePath().normalize();
        }

        Path demoSource = findDemoSource();
        log.warn("[DTConfig] No --source specified. Running demo source: " + demoSource);
        return demoSource;
    }

    private static Path findDemoSource() {
        Path[] candidates = {
                DEMO_SOURCE,
                Path.of("demo", "Test.java")
        };
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) {
                return absolute;
            }
        }
        return DEMO_SOURCE.toAbsolutePath().normalize();
    }

    private static int toExitCode(DTType type) {
        return switch (type) {
            case MATCH_SUCCESS -> 0;
            case MATCH_FAILURE, MATCH_TIMEOUT -> 1;
            case DIFFERENCE, DIFFERENCE_STDOUT, CRASH -> 2;
            case ENVIRONMENT_ERROR -> 3;
        };
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -jar difftest-core-1.0-SNAPSHOT-cli.jar [--source <file.java>] [options]

                Options:
                  -s, --source <file.java>             Java source file to test
                  -m, --main-class <name>              Main class name, defaults to source file name
                  -c, --config <file.yaml>             Extra YAML config file, loaded after bundled defaults
                  -h, --help                           Show this help

                Example:
                  java -jar difftest-core-1.0-SNAPSHOT-cli.jar \\
                    --source ./Example.java \\
                    --main-class Example \\
                    --config ./difftest.yaml

                  java -jar difftest-core-1.0-SNAPSHOT-cli.jar
                  # Runs difftest-core/demo/Test.java as a demo.

                Logging:
                  Set difftest.log_level and difftest.log_file in YAML.
                  Override with -Dlog.level=DEBUG -Ddifftest.log_file=logs/difftest.log.
                """);
    }

    private static final class CliOptions {
        private Path sourceFile;
        private String mainClassName;
        private boolean help;
        private final Map<String, String> overrides = new LinkedHashMap<>();

        private void putOverride(String key, String value) {
            overrides.put(key, value);
        }
    }
}
