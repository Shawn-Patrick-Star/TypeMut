package org.seed.config;

import org.difftest.config.DTConfig;
import org.seed.util.PlaceholderResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public class SeedFilterConfigLoader {
    static final String CONFIG_FILE = "seed-filter.yaml";
    static final String EXTERNAL_CONFIG_ARG = "seed.filter.config";
    private static final Set<String> OVERRIDE_PREFIXES = Set.of("SeedFilter.");

    private final YamlConfigLoader config;

    public SeedFilterConfigLoader() {
        this(new YamlConfigLoader(CONFIG_FILE, EXTERNAL_CONFIG_ARG, OVERRIDE_PREFIXES));
    }

    public SeedFilterConfigLoader(Path configFile) throws IOException {
        this(loadConfigFile(configFile));
    }

    SeedFilterConfigLoader(YamlConfigLoader config) {
        this.config = config;
    }

    public SeedFilterConfig load() {
        return fromProperties(config.properties(), builtInDefaults());
    }

    public static SeedFilterConfig fromProperties(Properties properties) {
        PlaceholderResolver.resolveAll(properties);
        return fromProperties(properties, new SeedFilterConfigLoader().load());
    }

    static SeedFilterConfig builtInDefaults() {
        return new SeedFilterConfig(
                null,
                Path.of("logs/seed-filter"),
                "INFO",
                Path.of("logs/seed-filter/seed-filter.log"),
                false,
                true,
                true,
                true,
                true,
                true,
                Path.of("logs/seed-filter/seed-filter-cache.properties"),
                resolveParallelism());
    }

    private static SeedFilterConfig fromProperties(Properties properties, SeedFilterConfig defaults) {
        PlaceholderResolver.resolveAll(properties);
        return new SeedFilterConfig(
                getOptionalPath(properties, "SeedFilter.input_dir", defaults.inputDir()),
                Path.of(getString(properties, "SeedFilter.output_dir", defaults.outputDir().toString())),
                getString(properties, "SeedFilter.log_level", defaults.logLevel()),
                getNullablePath(properties, "SeedFilter.log_file", defaults.logFile()),
                getBoolean(properties, "SeedFilter.keep_rejected_case", defaults.keepRejectedCase()),
                getBoolean(properties, "SeedFilter.copy_accepted_case", defaults.copyAcceptedCase()),
                getBoolean(properties, "SeedFilter.static_filter_enabled", defaults.staticFilterEnabled()),
                getBoolean(properties, "SeedFilter.compile_filter_enabled", defaults.compileFilterEnabled()),
                getBoolean(properties, "SeedFilter.JVMDT_filter_enabled", defaults.jvmDtFilterEnabled()),
                getBoolean(properties, "SeedFilter.use_cache", defaults.useCache()),
                Path.of(getString(properties, "SeedFilter.cache_file", defaults.cacheFile().toString())),
                defaults.parallelism());
    }

    private static YamlConfigLoader loadConfigFile(Path configFile) throws IOException {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Seed filter config file does not exist: " + configFile);
        }
        String name = configFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return new YamlConfigLoader(CONFIG_FILE, configFile, OVERRIDE_PREFIXES);
        }

        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configFile),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        PlaceholderResolver.resolveAll(properties);
        return new PropertiesBackedYamlConfigLoader(properties);
    }

    private static String getString(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue).trim();
    }

    private static Path getOptionalPath(Properties properties, String key, Path defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Path.of(value.trim());
    }

    private static Path getNullablePath(Properties properties, String key, Path defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if (value.trim().isEmpty()) {
            return null;
        }
        return Path.of(value.trim());
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(defaultValue)).trim());
    }

    private static int resolveParallelism() {
        return DTConfig.INITIAL_WORKERS;
    }

    private static final class PropertiesBackedYamlConfigLoader extends YamlConfigLoader {
        private final Properties loadedProperties;

        private PropertiesBackedYamlConfigLoader(Properties properties) {
            super(CONFIG_FILE, (String) null, OVERRIDE_PREFIXES);
            this.loadedProperties = new Properties();
            this.loadedProperties.putAll(properties);
        }

        @Override
        public Properties properties() {
            Properties copy = super.properties();
            copy.putAll(loadedProperties);
            return copy;
        }
    }
}
