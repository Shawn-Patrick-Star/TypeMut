package org.difftest.config;

                                                                       
public record DTResource(
        CpuTopology topology,
    int compilerTargets,
    int jvmTargets,
    int workerTargets,
    int initialWorkers,
    int workerUpperBound,
        int targetExecutorThreads,
    int ioReaderThreads) {

    public static DTResource from(boolean useCompilerDt, boolean useJvmDt,
            int compilerCount, int jvmCount) {
        return from(useCompilerDt, useJvmDt, compilerCount, jvmCount, CpuTopologyDetector.detect());
    }

    public static DTResource from(boolean useCompilerDt, boolean useJvmDt,
            int compilerCount, int jvmCount,
            CpuTopology topology) {
        int physical = topology.physicalCores();
            int effectiveCompilers = Math.max(0, compilerCount);
            int effectiveJvms = Math.max(0, jvmCount);
            int workerTargets = useJvmDt && effectiveJvms > 0
                ? effectiveJvms
                : useCompilerDt && effectiveCompilers > 0 ? effectiveCompilers : 1;
            int initialWorkers = Math.max(1, physical / Math.max(1, workerTargets));
            int workerUpperBound = Math.max(initialWorkers, physical);

            return new DTResource(topology, effectiveCompilers, effectiveJvms, workerTargets,
                initialWorkers, workerUpperBound, Math.max(1, physical),
                Math.max(2, physical * 2));
    }

    public int detectedCpus() { return topology.logicalProcessors(); }
            public int physicalCores() { return topology.physicalCores(); }
            public int activeTargetCount() { return workerTargets; }
            public int recommendedPipelineClients() { return initialWorkers; }
}
