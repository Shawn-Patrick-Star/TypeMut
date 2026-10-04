package org.difftest.config;

                                                   
public record CpuTopology(
        int logicalProcessors,
        int physicalCores,
        int threadsPerCore,
        Source source) {

    public CpuTopology {
        logicalProcessors = Math.max(1, logicalProcessors);
        physicalCores = Math.max(1, Math.min(logicalProcessors, physicalCores));
        threadsPerCore = Math.max(1, threadsPerCore);
        source = source == null ? Source.CONSERVATIVE_FALLBACK : source;
    }

    public enum Source {
        LINUX_SYSFS,
        LINUX_SYSFS_CGROUP,
        CONSERVATIVE_FALLBACK
    }
}
