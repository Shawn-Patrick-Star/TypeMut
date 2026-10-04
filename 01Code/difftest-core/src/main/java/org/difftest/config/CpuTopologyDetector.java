package org.difftest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

                                                                       
public final class CpuTopologyDetector {
    private static final Path PROC_STATUS = Path.of("/proc/self/status");
    private static final Path CPU_ROOT = Path.of("/sys/devices/system/cpu");

    private CpuTopologyDetector() {}

    public static CpuTopology detect() {
        int runtimeProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return fallback(runtimeProcessors);
        }
        try {
            String allowedList = readAllowedCpuList();
            Set<Integer> allowed = parseCpuList(allowedList);
            if (allowed.isEmpty()) {
                for (int cpu = 0; cpu < runtimeProcessors; cpu++) allowed.add(cpu);
            }
            Map<Integer, CoreIdentity> identities = new HashMap<>();
            for (int cpu : allowed) {
                Path topology = CPU_ROOT.resolve("cpu" + cpu).resolve("topology");
                identities.put(cpu, new CoreIdentity(
                        readInt(topology.resolve("physical_package_id")),
                        readInt(topology.resolve("core_id"))));
            }
            return fromLinuxData(runtimeProcessors, allowedList, identities,
                    CgroupCpuLimitDetector.detect());
        } catch (RuntimeException | IOException e) {
            return fallback(runtimeProcessors);
        }
    }

    static CpuTopology fromLinuxData(int runtimeProcessors, String allowedList,
            Map<Integer, CoreIdentity> identities) {
        return fromLinuxData(runtimeProcessors, allowedList, identities, OptionalInt.empty());
    }

    static CpuTopology fromLinuxData(int runtimeProcessors, String allowedList,
            Map<Integer, CoreIdentity> identities, OptionalInt quotaProcessors) {
        Set<Integer> allowed = parseCpuList(allowedList);
        int logical = Math.max(1, runtimeProcessors);
        if (!allowed.isEmpty()) logical = Math.min(logical, allowed.size());
        if (quotaProcessors != null && quotaProcessors.isPresent()) {
            logical = Math.min(logical, Math.max(1, quotaProcessors.getAsInt()));
        }
        Set<CoreIdentity> distinctCores = new HashSet<>();
        if (allowed.isEmpty()) {
            distinctCores.addAll(identities.values());
        } else {
            for (int cpu : allowed) {
                CoreIdentity identity = identities.get(cpu);
                if (identity != null) distinctCores.add(identity);
            }
        }
        if (distinctCores.isEmpty()) return fallback(logical);

        int physical = Math.max(1, Math.min(logical, distinctCores.size()));
        int threads = Math.max(1, (int) Math.ceil((double) logical / physical));
        CpuTopology.Source source = quotaProcessors != null && quotaProcessors.isPresent()
                ? CpuTopology.Source.LINUX_SYSFS_CGROUP
                : CpuTopology.Source.LINUX_SYSFS;
        return new CpuTopology(logical, physical, threads, source);
    }

    static CpuTopology fallback(int runtimeProcessors) {
        int logical = Math.max(1, runtimeProcessors);
        int physical = Math.max(1, (logical + 1) / 2);
        int threads = Math.max(1, (int) Math.ceil((double) logical / physical));
        return new CpuTopology(logical, physical, threads, CpuTopology.Source.CONSERVATIVE_FALLBACK);
    }

    static Set<Integer> parseCpuList(String value) {
        Set<Integer> cpus = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return cpus;
        for (String token : value.trim().split(",")) {
            String part = token.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash < 0) {
                cpus.add(Integer.parseInt(part));
                continue;
            }
            int start = Integer.parseInt(part.substring(0, dash));
            int end = Integer.parseInt(part.substring(dash + 1));
            for (int cpu = Math.min(start, end); cpu <= Math.max(start, end); cpu++) cpus.add(cpu);
        }
        return cpus;
    }

    private static String readAllowedCpuList() throws IOException {
        for (String line : Files.readAllLines(PROC_STATUS)) {
            if (line.startsWith("Cpus_allowed_list:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "";
    }

    private static int readInt(Path path) throws IOException {
        return Integer.parseInt(Files.readString(path).trim());
    }

    record CoreIdentity(int packageId, int coreId) {}
}
