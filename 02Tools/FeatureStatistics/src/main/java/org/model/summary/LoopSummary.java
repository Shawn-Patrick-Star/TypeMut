package org.model.summary;

import lombok.Getter;

import java.util.Map;

@Getter
public class LoopSummary extends Summary {
    private final int loopNumGlobal;
    private final Map<String, Integer> globalDepthDist;
    private final Map<String, Integer> globalCountDist;

    public LoopSummary(int totalCases,
                       int hasFeatureCases,
                       int loopNumGlobal,
                       Map<String, Integer> globalDepthDist,
                       Map<String, Integer> globalCountDist)
    {
        super(totalCases, hasFeatureCases, null);
        this.loopNumGlobal = loopNumGlobal;
        this.globalDepthDist = globalDepthDist;
        this.globalCountDist = globalCountDist;
    }
}
