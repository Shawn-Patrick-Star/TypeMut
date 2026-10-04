package org;

import org.ASTfeature.SourceCodeFeature;
import org.analysis.BatchProcessor;

import java.util.HashSet;
import java.util.Set;

public class Main {

    private static final int DEFAULT_SCAN_TIMEOUT_SECONDS = 10;

    public static void main(String[] args) {
        Options options = parseOptions(args);
        if (options == null) {
            printUsage();
            return;
        }

        Set<SourceCodeFeature> targets = new HashSet<>();
        new BatchProcessor(targets, options.scanTimeoutSeconds, options.deleteCases).run(options.targetDir);
    }

    private static Options parseOptions(String[] args) {
        Options options = new Options();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (isHelp(arg)) {
                return null;
            }

            if ("--delete".equals(arg)) {
                options.deleteCases = true;
                continue;
            }

            if ("--targetDir".equals(arg) || "-t".equals(arg)) {
                if (++i >= args.length) {
                    return null;
                }
                options.targetDir = args[i];
                continue;
            }

            if (arg.startsWith("--targetDir=")) {
                options.targetDir = arg.substring("--targetDir=".length());
                continue;
            }

            if (arg.startsWith("targetDir=")) {
                options.targetDir = arg.substring("targetDir=".length());
                continue;
            }

            if ("--scanTimeoutSeconds".equals(arg) || "--timeout".equals(arg)) {
                if (++i >= args.length || !setTimeout(options, args[i])) {
                    return null;
                }
                continue;
            }

            if (arg.startsWith("--scanTimeoutSeconds=")) {
                if (!setTimeout(options, arg.substring("--scanTimeoutSeconds=".length()))) {
                    return null;
                }
                continue;
            }

            if (arg.startsWith("--timeout=")) {
                if (!setTimeout(options, arg.substring("--timeout=".length()))) {
                    return null;
                }
                continue;
            }

            if (options.targetDir == null) {
                options.targetDir = arg;
                continue;
            }

            return null;
        }

        return options.targetDir == null ? null : options;
    }

    private static boolean setTimeout(Options options, String value) {
        try {
            options.scanTimeoutSeconds = Integer.parseInt(value);
            return options.scanTimeoutSeconds > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar FeatureStatistics-1.0-SNAPSHOT.jar <targetDir>");
        System.out.println("  java -jar FeatureStatistics-1.0-SNAPSHOT.jar --targetDir <targetDir>");
        System.out.println("  java -jar FeatureStatistics-1.0-SNAPSHOT.jar --targetDir=<targetDir>");
        System.out.println("  java -jar FeatureStatistics-1.0-SNAPSHOT.jar -t <targetDir>");
        System.out.println("Options:");
        System.out.println("  --scanTimeoutSeconds <seconds>  Timeout for each Java source scan. Default: 10");
        System.out.println("  --timeout <seconds>             Alias for --scanTimeoutSeconds");
        System.out.println("  --delete                        Delete cases with no detected features or cases skipped during analysis");
    }

    private static class Options {
        private String targetDir;
        private int scanTimeoutSeconds = DEFAULT_SCAN_TIMEOUT_SECONDS;
        private boolean deleteCases = false;
    }
}
