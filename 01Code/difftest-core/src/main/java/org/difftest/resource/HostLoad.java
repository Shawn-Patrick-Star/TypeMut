package org.difftest.resource;

                                                                           
public record HostLoad(double cpuLoad, double freeMemoryRatio) {
    public HostLoad {
        cpuLoad = normalize(cpuLoad);
        freeMemoryRatio = normalize(freeMemoryRatio);
    }

    private static double normalize(double value) {
        if (Double.isNaN(value) || value < 0.0d) return -1.0d;
        return Math.min(1.0d, value);
    }
}
