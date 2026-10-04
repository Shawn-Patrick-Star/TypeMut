package org.fuzz.config;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.util.PlaceholderResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Slf4j
public class YamlConfigLoader {
    private static final Set<String> PROFILE_KEYS = Set.of("linux", "win", "windows", "mac", "macos");

    private final String defaultResource;
    private final String externalConfigProperty;
    private final Path externalConfigFile;
    private final Set<String> overridePrefixes;
    private final Properties properties = new Properties();
    private final Yaml yaml = new Yaml();

    public YamlConfigLoader(String defaultResource, String externalConfigProperty, Set<String> overridePrefixes) {
        this(defaultResource, externalConfigProperty, null, overridePrefixes);
    }

    public YamlConfigLoader(String defaultResource, Path externalConfigFile, Set<String> overridePrefixes) {
        this(defaultResource, null, externalConfigFile, overridePrefixes);
    }

    private YamlConfigLoader(String defaultResource,
                             String externalConfigProperty,
                             Path externalConfigFile,
                             Set<String> overridePrefixes) {
        this.defaultResource = defaultResource;
        this.externalConfigProperty = externalConfigProperty;
        this.externalConfigFile = externalConfigFile;
        this.overridePrefixes = overridePrefixes == null ? Set.of() : Set.copyOf(overridePrefixes);
        load();
    }

    private void load() {
        loadYamlResource(defaultResource);

        Path explicitConfig = externalConfigFile;
        if (explicitConfig == null && externalConfigProperty != null) {
            String customPath = System.getProperty(externalConfigProperty);
            if (customPath != null && !customPath.trim().isEmpty()) {
                explicitConfig = Path.of(customPath.trim());
            }
        }
        if (explicitConfig != null) {
            loadExternalConfig(explicitConfig);
        }

        applySystemPropertyOverrides();
        PlaceholderResolver.resolveAll(properties);
    }

    private void loadExternalConfig(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Config file does not exist: " + configFile);
        }
        log.info("[Config] Loading external configuration: {}", configFile);
        try (InputStream input = new FileInputStream(configFile.toFile())) {
            loadYaml(input, configFile.toString());
        } catch (IOException e) {
            log.error("[Config] Failed to load external config file: {}", configFile, e);
            throw new RuntimeException(e);
        }
    }

    private void loadYamlResource(String fileName) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (input != null) {
                loadYaml(input, fileName);
            } else {
                log.error("[Config] No {} found in classpath", fileName);
            }
        } catch (IOException e) {
            log.error("[Config] Error loading resource {}: {}", fileName, e.getMessage());
        }
    }

    private void loadYaml(InputStream input, String sourceName) {
        Object root = yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        if (root == null) {
            return;
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("YAML config root must be a mapping: " + sourceName);
        }
        flattenYamlDocument(rootMap);
    }

    private void flattenYamlDocument(Map<?, ?> rootMap) {
        for (Map.Entry<?, ?> entry : rootMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            if (!isProfileKey(key)) {
                flattenYamlValue(key, entry.getValue());
            }
        }

        for (String profile : currentProfileKeys()) {
            Object profileValue = findProfileValue(rootMap, profile);
            if (profileValue instanceof Map<?, ?> profileMap) {
                for (Map.Entry<?, ?> entry : profileMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        flattenYamlValue(entry.getKey().toString(), entry.getValue());
                    }
                }
            }
        }
        overlayNestedProfileSections("", rootMap);
    }

    private boolean isProfileKey(String key) {
        return PROFILE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private List<String> currentProfileKeys() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return List.of("win", "windows");
        }
        if (osName.contains("mac")) {
            return List.of("mac", "macos");
        }
        if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return List.of("linux");
        }
        return List.of();
    }

    private Object findProfileValue(Map<?, ?> rootMap, String profile) {
        for (Map.Entry<?, ?> entry : rootMap.entrySet()) {
            if (entry.getKey() != null && profile.equalsIgnoreCase(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void overlayNestedProfileSections(String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            if (isProfileKey(key) || !(entry.getValue() instanceof Map<?, ?> childMap)) {
                continue;
            }

            String childPrefix = prefix.isEmpty() ? key : prefix + "." + key;
            for (String profile : currentProfileKeys()) {
                Object profileValue = findProfileValue(childMap, profile);
                if (profileValue instanceof Map<?, ?> profileMap) {
                    flattenYaml(childPrefix, profileMap);
                }
            }
            overlayNestedProfileSections(childPrefix, childMap);
        }
    }

    private void flattenYamlValue(String key, Object value) {
        if (value instanceof Map<?, ?> childMap) {
            flattenYaml(key, childMap);
        } else if (value instanceof Iterable<?> iterable) {
            properties.setProperty(key, joinIterable(iterable));
        } else {
            properties.setProperty(key, value.toString());
        }
    }

    private void flattenYaml(String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = prefix.isEmpty() ? entry.getKey().toString() : prefix + "." + entry.getKey();
            flattenYamlValue(key, entry.getValue());
        }
    }

    private String joinIterable(Iterable<?> iterable) {
        List<String> values = new ArrayList<>();
        for (Object value : iterable) {
            if (value != null) {
                values.add(value.toString());
            }
        }
        return String.join(",", values);
    }

    private void applySystemPropertyOverrides() {
        for (String key : System.getProperties().stringPropertyNames()) {
            if (isAllowedOverride(key)) {
                properties.setProperty(key, System.getProperty(key));
            }
        }
    }

    private boolean isAllowedOverride(String key) {
        return overridePrefixes.stream().anyMatch(key::startsWith);
    }

    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)).trim());
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Integer getOptionalInt(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }
}
