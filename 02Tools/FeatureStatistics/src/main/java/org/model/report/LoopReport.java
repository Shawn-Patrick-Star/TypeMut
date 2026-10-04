package org.model.report;

import lombok.Getter;

import java.util.Map;

@Getter
public class LoopReport {
    private final String caseName;
    private final int loopNum;
    // Depth distribution: "Depth-1" -> 5, "Depth-2" -> 3
    private final Map<String, Integer> depthDistribution;
    // Count distribution: "[0, 10]": 2, "Unknown": 4
    private final Map<String, Integer> countDistribution;

    public LoopReport(String caseName, int loopNum, Map<String, Integer> depthDistribution, Map<String, Integer> countDistribution) {
        this.caseName = caseName;
        this.loopNum = loopNum;
        this.depthDistribution = depthDistribution;
        this.countDistribution = countDistribution;
    }
}