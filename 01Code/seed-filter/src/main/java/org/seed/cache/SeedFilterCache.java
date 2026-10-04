package org.seed.cache;

import org.difftest.config.DTConfig;
import org.difftest.model.instance.CompilerInstance;
import org.difftest.model.instance.JvmInstance;
import org.seed.model.RejectionReason;
import org.seed.model.Seed;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class SeedFilterCache implements AutoCloseable {

    private static final String KEY_PREFIX = "v3.";
    private static final String ACCEPTED = "A";
    private static final String REJECTED = "R";

    private final Path cacheFile;
    private final boolean enabled;
    private final String scope;
    private final Properties entries = new Properties();
    private boolean dirty = false;

    public SeedFilterCache(Path cacheFile, boolean enabled) {
        this(cacheFile, enabled, "");
    }

    public SeedFilterCache(Path cacheFile, boolean enabled, String scope) {
        this.cacheFile = cacheFile;
        this.enabled = enabled && cacheFile != null;
        this.scope = scope == null ? "" : scope;
        load();
        ensureCacheFileExists();
    }

    public Optional<CachedDecision> get(Seed seed) {
        if (!enabled) {
            return Optional.empty();
        }
        String seedKey = key(seed);
        String value;
        synchronized (this) {
            value = entries.getProperty(seedKey);
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return CachedDecision.parse(value);
    }

    public void putAccepted(Seed seed) {
        put(seed, CachedDecision.accept());
    }

    public void putRejected(Seed seed, RejectionReason reason, String ruleName, String detail) {
        put(seed, CachedDecision.rejected(reason, ruleName, detail));
    }

    private void put(Seed seed, CachedDecision decision) {
        if (!enabled) {
            return;
        }
        String seedKey = key(seed);
        synchronized (this) {
            entries.setProperty(seedKey, decision.serialize());
            dirty = true;
            flush();
        }
    }

    private void load() {
        if (!enabled || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(cacheFile), StandardCharsets.UTF_8)) {
            entries.load(reader);
        } catch (IOException ignored) {
            entries.clear();
        }
    }

    @Override
    public synchronized void close() {
        if (!enabled || !dirty) {
            return;
        }
        flush();
    }

    private void ensureCacheFileExists() {
        if (!enabled || Files.isRegularFile(cacheFile)) {
            return;
        }
        synchronized (this) {
            dirty = true;
            flush();
        }
    }

    private void flush() {
        if (!enabled || !dirty) {
            return;
        }
        Path tempFile = null;
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempDir = parent == null ? Path.of(".") : parent;
            tempFile = Files.createTempFile(tempDir, cacheFile.getFileName().toString(), ".tmp");
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(tempFile),
                    StandardCharsets.UTF_8)) {
                entries.store(writer, "SeedFilter DTPipeline cache. Safe to delete.");
            }
            moveIntoPlace(tempFile, cacheFile);
            tempFile = null;
            dirty = false;
        } catch (IOException ignored) {
                                                    
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                                                
                }
            }
        }
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String key(Seed seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, scope);
            updateDifftestEnvironment(digest);
            update(digest, seed.getId());
            update(digest, seed.getMainFileName());
            update(digest, seed.getMainClassName());
            for (Path file : seedFiles(seed)) {
                update(digest, file.getFileName().toString());
                if (Files.isRegularFile(file)) {
                    digest.update(Files.readAllBytes(file));
                }
            }
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return KEY_PREFIX + Integer.toHexString(seed.toString().hashCode());
        }
    }
    private void updateDifftestEnvironment(MessageDigest digest) {
        update(digest, "difftest");
        DTConfig.COMPILERS.stream()
                .sorted(Comparator.comparing(CompilerInstance::getId))
                .forEach(compiler -> {
                    update(digest, "compiler");
                    update(digest, compiler.getId());
                    update(digest, compiler.getVersionInfo());
                });
        DTConfig.JVMS.stream()
                .sorted(Comparator.comparing(JvmInstance::getId))
                .forEach(jvm -> {
                    update(digest, "jvm");
                    update(digest, jvm.getId());
                    update(digest, jvm.getVersionInfo());
                });
    }

    private List<Path> seedFiles(Seed seed) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(seed.getMainFilePath()),
                        seed.getCompanionFilePaths().stream())
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
    }

    private void update(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }

    public record CachedDecision(boolean accepted, RejectionReason reason, String ruleName, String detail) {
        public static CachedDecision accept() {
            return new CachedDecision(true, null, "", "");
        }

        public static CachedDecision rejected(RejectionReason reason, String ruleName, String detail) {
            return new CachedDecision(false, reason, nullToEmpty(ruleName), nullToEmpty(detail));
        }

        private static Optional<CachedDecision> parse(String value) {
            String[] parts = value.split("\\|", -1);
            if (parts.length == 1 && ACCEPTED.equals(parts[0])) {
                return Optional.of(accept());
            }
            if (parts.length == 4 && REJECTED.equals(parts[0])) {
                try {
                    return Optional.of(rejected(
                            RejectionReason.valueOf(parts[1]),
                            decode(parts[2]),
                            decode(parts[3])));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }

        private String serialize() {
            if (accepted) {
                return ACCEPTED;
            }
            return REJECTED + "|" + reason.name() + "|" + encode(ruleName) + "|" + encode(detail);
        }

        private static String encode(String value) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
        }

        private static String decode(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
