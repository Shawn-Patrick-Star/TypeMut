package org.seed.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class SeedFilterResult {
    private final List<Seed> acceptedSeeds;
    private final List<RejectedSeed> rejectedSeeds;
    private final Map<RejectionReason, Long> rejectionCounts;

    public SeedFilterResult(List<Seed> acceptedSeeds, List<RejectedSeed> rejectedSeeds) {
        this.acceptedSeeds = List.copyOf(acceptedSeeds);
        this.rejectedSeeds = List.copyOf(rejectedSeeds);
        this.rejectionCounts = buildCounts(rejectedSeeds);
    }

    public List<Seed> acceptedSeeds() {
        return acceptedSeeds;
    }

    public List<RejectedSeed> rejectedSeeds() {
        return rejectedSeeds;
    }

    public Map<RejectionReason, Long> rejectionCounts() {
        return Map.copyOf(rejectionCounts);
    }

    public long acceptedCount() {
        return acceptedSeeds.size();
    }

    public long rejectedCount() {
        return rejectedSeeds.size();
    }

    public long staticFilteredCount() {
        return count(RejectionReason.STATIC_TIME)
                + count(RejectionReason.STATIC_THREAD)
                + count(RejectionReason.STATIC_SYSTEM);
    }

    public long compileFailedCount() {
        return count(RejectionReason.COMPILE_FAILED);
    }

    public long rawDiffFailedCount() {
        return count(RejectionReason.RAW_DIFF_FAILURE)
                + count(RejectionReason.RAW_TIMEOUT)
                + count(RejectionReason.RAW_MATCH_FAILURE)
                + count(RejectionReason.RAW_ENVIRONMENT_ERROR);
    }

    public long count(RejectionReason reason) {
        return rejectionCounts.getOrDefault(reason, 0L);
    }

    private Map<RejectionReason, Long> buildCounts(List<RejectedSeed> rejectedSeeds) {
        EnumMap<RejectionReason, Long> counts = new EnumMap<>(RejectionReason.class);
        for (RejectedSeed rejected : rejectedSeeds) {
            counts.merge(rejected.reason(), 1L, Long::sum);
        }
        return counts;
    }
}

