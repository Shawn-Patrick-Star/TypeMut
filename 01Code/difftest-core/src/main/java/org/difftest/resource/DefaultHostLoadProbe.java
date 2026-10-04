package org.difftest.resource;

import java.lang.management.ManagementFactory;

                                                                           
public final class DefaultHostLoadProbe implements HostLoadProbe {
    @Override
    public HostLoad sample() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean os)) {
            return new HostLoad(-1.0d, -1.0d);
        }
        double cpu = os.getCpuLoad();
        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        double freeRatio = total > 0L ? (double) free / total : -1.0d;
        return new HostLoad(cpu, freeRatio);
    }
}
