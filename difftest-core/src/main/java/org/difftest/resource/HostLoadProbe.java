package org.difftest.resource;

@FunctionalInterface
public interface HostLoadProbe {
    HostLoad sample();
}
