package org.difftest.config;

import lombok.extern.slf4j.Slf4j;
import org.difftest.model.instance.CompilerInstance;
import org.difftest.model.instance.JvmInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
public class DTConfigLoader {
    static final String CONFIG_FILE = "difftest.yaml";
    static final String EXTERNAL_CONFIG_ARG = "difftest.config";
    private static final Set<String> OVERRIDE_PREFIXES = Set.of(
            "difftest.", "compiler.", "jvm.");

    private final YamlConfigLoader config;

    private List<CompilerInstance> cachedCompilerConfigs;
    private List<JvmInstance> cachedJvmConfigs;

    DTConfigLoader() {
        this(new YamlConfigLoader(CONFIG_FILE, EXTERNAL_CONFIG_ARG, OVERRIDE_PREFIXES));
    }

    DTConfigLoader(YamlConfigLoader config) {
        this.config = config;
    }


    public List<CompilerInstance> loadCompilerConfigs() {
        if (cachedCompilerConfigs != null) {
            return cachedCompilerConfigs;
        }
        List<CompilerInstance> configured = loadInstances("difftest.active_compilers", "compiler.",
                (id, attrs, cmd) -> {
                    CompilerInstance.Builder builder = new CompilerInstance.Builder(id).setJavacPath(cmd.executable());
                    if (!cmd.args().isEmpty()) {
                        builder.addArgs(cmd.args().toArray(String[]::new));
                    }
                    addArgsIfPresent(builder::addArgs, attrs.get("args"));
                    return builder.build();
                });
        validateCompilerConfig(configured);
        cachedCompilerConfigs = Collections.unmodifiableList(selectEffectiveCompilers(configured));
        return cachedCompilerConfigs;
    }

    public List<JvmInstance> loadJvmConfigs() {
        if (cachedJvmConfigs != null) {
            return cachedJvmConfigs;
        }
        if (!hasRuntimeStage()) {
            return cachedJvmConfigs = Collections.emptyList();
        }
        List<JvmInstance> configured = loadInstances("difftest.active_jvms", "jvm.",
                (id, attrs, cmd) -> {
                    JvmInstance.Builder builder = new JvmInstance.Builder(id).setJavaPath(cmd.executable());
                    if (!cmd.args().isEmpty()) {
                        builder.addArgs(cmd.args().toArray(String[]::new));
                    }
                    addArgsIfPresent(builder::addArgs, attrs.get("args"));
                    return builder.build();
                });
        validateJvmConfig(configured);
        cachedJvmConfigs = Collections.unmodifiableList(selectEffectiveJvms(configured));
        return cachedJvmConfigs;
    }

    public DTResource loadResource(
            boolean useCompilerDt, boolean useJvmDt,
            List<CompilerInstance> compilers, List<JvmInstance> jvms) {
        return DTResource.from(useCompilerDt, useJvmDt,
                compilers == null ? 0 : compilers.size(),
                jvms == null ? 0 : jvms.size());
    }

    @FunctionalInterface
    private interface InstanceBuilder<T> {
        T build(String id, Map<String, String> attrs, CommandParts cmd);
    }

    private <T> List<T> loadInstances(String activeKey, String prefix, InstanceBuilder<T> builder) {
        String activeStr = activeKey == null ? "" : config.getString(activeKey, "");
        List<String> activeIds = Arrays.stream(activeStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        Set<String> activeSet = new HashSet<>(activeIds);

        Map<String, Map<String, String>> groupedAttrs = new HashMap<>();
        for (String key : config.properties().stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String[] parts = key.split("\\.", 3);
            if (parts.length < 2) {
                continue;
            }

            String id = parts[1];
            if (!activeSet.isEmpty() && !activeSet.contains(id)) {
                continue;
            }

            if (parts.length == 2) {
                groupedAttrs.computeIfAbsent(id, ignored -> new HashMap<>())
                        .put("inline_cmd", config.getString(key, ""));
            } else {
                groupedAttrs.computeIfAbsent(id, ignored -> new HashMap<>())
                        .put(parts[2], config.getString(key, ""));
            }
        }

        List<String> order = activeIds.isEmpty() ? new ArrayList<>(groupedAttrs.keySet()) : activeIds;
        List<T> result = new ArrayList<>();

        for (String id : order) {
            Map<String, String> attrs = groupedAttrs.get(id);
            if (attrs == null) {
                continue;
            }

            String path = attrs.get("path");
            String inlineCmd = attrs.get("inline_cmd");
            CommandParts cmdParts;
            if (inlineCmd != null && !inlineCmd.trim().isEmpty()) {
                cmdParts = parseCommand(inlineCmd);
            } else if (path != null && !path.trim().isEmpty()) {
                cmdParts = parseCommand(path);
            } else {
                log.warn("[DTConfig] {} '{}' is active but missing 'path' or inline command, skipped.",
                        prefix.replace(".", ""), id);
                continue;
            }

            T instance = builder.build(id, attrs, cmdParts);
            if (instance != null) {
                result.add(instance);
            }
        }
        return result;
    }

    private void validateCompilerConfig(List<CompilerInstance> configuredCompilers) {
        int count = configuredCompilers == null ? 0 : configuredCompilers.size();
        if (count == 0) {
            throw configError("[DTConfig] No compiler is configured. Check difftest.active_compilers and compiler.* entries.");
        }
        if (getBoolean("difftest.use_compiler_dt", true) && count <= 1) {
            throw configError("[DTConfig] difftest.use_compiler_dt=true requires at least 2 active compilers, but found "
                    + count + ". Add another compiler or set difftest.use_compiler_dt=false.");
        }
    }

    private void validateJvmConfig(List<JvmInstance> configuredJvms) {
        if (!hasRuntimeStage()) {
            return;
        }
        int count = configuredJvms == null ? 0 : configuredJvms.size();
        if (count == 0) {
            throw configError("[DTConfig] No JVM is configured. Check difftest.active_jvms and jvm.* entries.");
        }
        if (getBoolean("difftest.use_jvm_dt", true) && count <= 1) {
            throw configError("[DTConfig] difftest.use_jvm_dt=true requires at least 2 active JVMs, but found "
                    + count + ". Add another JVM or set difftest.use_jvm_dt=false.");
        }
    }

    private boolean hasRuntimeStage() {
        return getBoolean("difftest.use_jvm_dt", true);
    }

    private IllegalStateException configError(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }

    private List<CompilerInstance> selectEffectiveCompilers(List<CompilerInstance> configuredCompilers) {
        if (getBoolean("difftest.use_compiler_dt", true)) {
            return configuredCompilers;
        }
        if (configuredCompilers == null || configuredCompilers.isEmpty()) {
            return configuredCompilers;
        }

        CompilerInstance selected = configuredCompilers.stream()
                .filter(compiler -> compiler.getId().toLowerCase(Locale.ROOT).contains("javac"))
                .findFirst()
                .orElse(configuredCompilers.get(0));
        log.warn("[DTConfig] NO CompilerDT; Using '{}' as baseline compiler.", selected.getId());
        return List.of(selected);
    }

    private List<JvmInstance> selectEffectiveJvms(List<JvmInstance> configuredJvms) {
        if (getBoolean("difftest.use_jvm_dt", true)) {
            return configuredJvms;
        }
        if (configuredJvms == null || configuredJvms.isEmpty()) {
            return configuredJvms;
        }

        JvmInstance selected = configuredJvms.stream()
                .filter(JvmInstance::isHotspotJvm)
                .findFirst()
                .orElse(configuredJvms.get(0));
        if (selected.isHotspotJvm()) {
            log.warn("[DTConfig] NO JVMDT; Using '{}' as baseline JVM.", selected.getId());
        } else {
            log.warn("[DTConfig] NO JVMDT, NO HotSpot JVM found; Using '{}' as baseline JVM.", selected.getId());
        }
        return List.of(selected);
    }

                                                                    
                        
                                                                    

    public String getString(String key, String defaultValue) {
        return config.getString(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return config.getBoolean(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return config.getInt(key, defaultValue);
    }

    public Integer getOptionalInt(String key) {
        return config.getOptionalInt(key);
    }
    
                                                                    
                      
                                                                    

    private void addArgsIfPresent(java.util.function.Consumer<String[]> consumer, String rawArgs) {
        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            consumer.accept(splitCommandLine(rawArgs).toArray(String[]::new));
        }
    }

    private CommandParts parseCommand(String rawCommand) {
        List<String> parts = splitCommandLine(rawCommand);
        return parts.isEmpty()
                ? new CommandParts("", List.of())
                : new CommandParts(parts.get(0), List.copyOf(parts.subList(1, parts.size())));
    }

    private List<String> splitCommandLine(String commandLine) {
        if (commandLine == null) {
            return new ArrayList<>();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (char ch : commandLine.toCharArray()) {
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private record CommandParts(String executable, List<String> args) {
    }
}
