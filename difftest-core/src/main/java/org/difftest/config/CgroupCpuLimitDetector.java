package org.difftest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

                                                                            
final class CgroupCpuLimitDetector {
    private static final Path V2_CPU_MAX = Path.of("/sys/fs/cgroup/cpu.max");
    private static final List<Path> V1_CPU_ROOTS = List.of(
            Path.of("/sys/fs/cgroup/cpu"),
            Path.of("/sys/fs/cgroup/cpu,cpuacct"));

    private CgroupCpuLimitDetector() {}

    static OptionalInt detect() {
        OptionalInt v2 = readV2(V2_CPU_MAX);
        if (v2.isPresent()) return v2;
        for (Path root : V1_CPU_ROOTS) {
            OptionalInt v1 = readV1(root);
            if (v1.isPresent()) return v1;
        }
        return OptionalInt.empty();
    }

    static OptionalInt parseV2(String value) {
        if (value == null) return OptionalInt.empty();
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2 || "max".equalsIgnoreCase(parts[0])) return OptionalInt.empty();
        return quotaProcessors(parts[0], parts[1]);
    }

    static OptionalInt parseV1(String quotaValue, String periodValue) {
        if (quotaValue == null || periodValue == null) return OptionalInt.empty();
        try {
            if (Long.parseLong(quotaValue.trim()) < 0L) return OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
        return quotaProcessors(quotaValue, periodValue);
    }

    private static OptionalInt readV2(Path path) {
        try {
            return Files.isRegularFile(path) ? parseV2(Files.readString(path)) : OptionalInt.empty();
        } catch (IOException | SecurityException e) {
            return OptionalInt.empty();
        }
    }

    private static OptionalInt readV1(Path root) {
        Path quota = root.resolve("cpu.cfs_quota_us");
        Path period = root.resolve("cpu.cfs_period_us");
        try {
            if (!Files.isRegularFile(quota) || !Files.isRegularFile(period)) return OptionalInt.empty();
            return parseV1(Files.readString(quota), Files.readString(period));
        } catch (IOException | SecurityException e) {
            return OptionalInt.empty();
        }
    }

    private static OptionalInt quotaProcessors(String quotaValue, String periodValue) {
        try {
            long quota = Long.parseLong(quotaValue.trim());
            long period = Long.parseLong(periodValue.trim());
            if (quota <= 0L || period <= 0L) return OptionalInt.empty();
            long processors = Math.max(1L, (quota + period - 1L) / period);
            return OptionalInt.of((int) Math.min(Integer.MAX_VALUE, processors));
        } catch (NumberFormatException | ArithmeticException e) {
            return OptionalInt.empty();
        }
    }
}
