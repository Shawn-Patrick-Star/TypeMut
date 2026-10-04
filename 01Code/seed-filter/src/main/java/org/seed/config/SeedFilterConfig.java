package org.seed.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public record SeedFilterConfig(
        Path inputDir,
        Path outputDir,
        String logLevel,
        Path logFile,
        boolean keepRejectedCase,
        boolean copyAcceptedCase,
        boolean staticFilterEnabled,
        boolean compileFilterEnabled,
        boolean jvmDtFilterEnabled,
        boolean useCache,
        Path cacheFile,
        int parallelism) {

    public SeedFilterConfig {
        if (!compileFilterEnabled && jvmDtFilterEnabled) {
            throw new IllegalArgumentException(
                    "SeedFilter.JVMDT_filter_enabled=true requires SeedFilter.compile_filter_enabled=true.");
        }
    }

    public static SeedFilterConfig defaults() {
        return new SeedFilterConfigLoader().load();
    }

    public static SeedFilterConfig fromProperties(Path configFile) throws IOException {
        return new SeedFilterConfigLoader(configFile).load();
    }

    public static SeedFilterConfig fromProperties(Properties properties) {
        return SeedFilterConfigLoader.fromProperties(properties);
    }

    public static SeedFilterConfig of(
            boolean keepRejectedCase,
            boolean staticFilterEnabled,
            boolean compileFilterEnabled,
            boolean jvmDtFilterEnabled) {
        return new SeedFilterConfig(
                null,
                Path.of("logs/seed-filter"),
                "INFO",
                Path.of("logs/seed-filter/seed-filter.log"),
                keepRejectedCase,
                true,
                staticFilterEnabled,
                compileFilterEnabled,
                jvmDtFilterEnabled,
                true,
                Path.of("logs/seed-filter/seed-filter-cache.properties"),
                resolveParallelism());
    }

    public static SeedFilterConfig of(
            boolean keepRejectedCase,
            boolean copyAcceptedCase,
            boolean staticFilterEnabled,
            boolean compileFilterEnabled,
            boolean jvmDtFilterEnabled,
            boolean useCache,
            Path cacheFile) {
        return of(keepRejectedCase, copyAcceptedCase, staticFilterEnabled,
                compileFilterEnabled, jvmDtFilterEnabled, useCache, cacheFile, resolveParallelism());
    }

    public static SeedFilterConfig of(
            boolean keepRejectedCase,
            boolean copyAcceptedCase,
            boolean staticFilterEnabled,
            boolean compileFilterEnabled,
            boolean jvmDtFilterEnabled,
            boolean useCache,
            Path cacheFile,
            int parallelism) {
        return new SeedFilterConfig(
                null,
                Path.of("logs/seed-filter"),
                "INFO",
                Path.of("logs/seed-filter/seed-filter.log"),
                keepRejectedCase,
                copyAcceptedCase,
                staticFilterEnabled,
                compileFilterEnabled,
                jvmDtFilterEnabled,
                useCache,
                cacheFile,
                parallelism);
    }

    public SeedFilterConfig withOutputDir(Path newOutputDir) {
        if (newOutputDir == null) {
            return this;
        }
        Path oldOutputDir = outputDir();
        return new SeedFilterConfig(
                inputDir(),
                newOutputDir,
                logLevel(),
                rebasePath(logFile(), oldOutputDir, newOutputDir),
                keepRejectedCase(),
                copyAcceptedCase(),
                staticFilterEnabled(),
                compileFilterEnabled(),
                jvmDtFilterEnabled(),
                useCache(),
                rebasePath(cacheFile(), oldOutputDir, newOutputDir),
                parallelism());
    }

    private static Path rebasePath(Path path, Path oldRoot, Path newRoot) {
        if (path == null || oldRoot == null || newRoot == null) {
            return path;
        }
        Path normalizedPath = path.normalize();
        Path normalizedOldRoot = oldRoot.normalize();
        if (normalizedPath.startsWith(normalizedOldRoot)) {
            return newRoot.resolve(normalizedOldRoot.relativize(normalizedPath)).normalize();
        }

        Path absolutePath = path.toAbsolutePath().normalize();
        Path absoluteOldRoot = oldRoot.toAbsolutePath().normalize();
        if (absolutePath.startsWith(absoluteOldRoot)) {
            return newRoot.resolve(absoluteOldRoot.relativize(absolutePath)).normalize();
        }
        return path;
    }

    private static int resolveParallelism() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
